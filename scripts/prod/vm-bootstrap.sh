#!/usr/bin/env bash
# Preparação base da VM de produção (Oracle Linux 9): disco, atualizações e SSH.
# Idempotente: pode ser executado de novo a qualquer momento.
# Uso na VM: sudo ./vm-bootstrap.sh   (ao final, reinicie se o script indicar)
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo 'Execute com sudo.' >&2; exit 1; }

echo '==> Disco: expande a partição e o sistema de arquivos até o tamanho do boot volume'
if [[ -x /usr/libexec/oci-growfs ]]; then
  /usr/libexec/oci-growfs -y || echo 'oci-growfs: nada a expandir.'
fi

echo '==> Atualizações: aplica todos os pacotes pendentes'
dnf -y upgrade

echo '==> Atualizações automáticas: só correções de segurança, sem reinício automático'
dnf -y install dnf-automatic
sed -i -E \
  -e 's/^upgrade_type *=.*/upgrade_type = security/' \
  -e 's/^apply_updates *=.*/apply_updates = yes/' \
  /etc/dnf/automatic.conf
systemctl enable --now dnf-automatic.timer

echo '==> SSH: só chave, sem root, poucas tentativas'
# Arquivos em sshd_config.d são lidos em ordem e o primeiro valor vence: o prefixo 01 garante prioridade.
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

echo '==> Resultado'
df -h /
if needs-restarting -r >/dev/null; then
  echo 'Reinício não é necessário.'
else
  echo 'REINÍCIO NECESSÁRIO: kernel ou bibliotecas base foram atualizados (sudo systemctl reboot).'
fi
