#!/usr/bin/env bash
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo 'Run with sudo.' >&2; exit 1; }

echo '==> Disk: expanding the partition and filesystem to the boot volume size'
if [[ -x /usr/libexec/oci-growfs ]]; then
  /usr/libexec/oci-growfs -y || echo 'oci-growfs: nothing to expand.'
fi

echo '==> Updates: applying all pending package updates'
dnf -y upgrade

echo '==> Automatic updates: security fixes only, no automatic reboot'
dnf -y install dnf-automatic
sed -i -E \
  -e 's/^upgrade_type *=.*/upgrade_type = security/' \
  -e 's/^apply_updates *=.*/apply_updates = yes/' \
  /etc/dnf/automatic.conf
systemctl enable --now dnf-automatic.timer

echo '==> Deployment tools: git (public repository clone) and OCI CLI (Vault secrets via instance principal)'
dnf -y install git python39-oci-cli

echo '==> Docker Engine and Compose (official Docker repository)'
dnf -y install dnf-plugins-core
[[ -f /etc/yum.repos.d/docker-ce.repo ]] \
  || dnf config-manager --add-repo https://download.docker.com/linux/centos/docker-ce.repo
dnf -y install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
daemon_json=$(mktemp)
cat > "$daemon_json" <<'EOF'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "5" },
  "live-restore": true
}
EOF
install -d -m 0755 /etc/docker
if ! cmp -s "$daemon_json" /etc/docker/daemon.json; then
  install -o root -g root -m 0644 "$daemon_json" /etc/docker/daemon.json
  systemctl restart docker
fi
rm -f "$daemon_json"
systemctl enable --now docker

echo '==> SSH: key authentication only, no root login, limited attempts'
cat > /etc/ssh/sshd_config.d/01-ecommerce-hardening.conf <<'EOF'
PermitRootLogin no
PasswordAuthentication no
KbdInteractiveAuthentication no
X11Forwarding no
MaxAuthTries 3
EOF
chmod 600 /etc/ssh/sshd_config.d/01-ecommerce-hardening.conf
sshd -t
systemctl reload sshd

echo '==> Result'
df -h /
if needs-restarting -r >/dev/null; then
  echo 'No reboot required.'
else
  echo 'REBOOT REQUIRED: the kernel or core libraries were updated (sudo systemctl reboot).'
fi
