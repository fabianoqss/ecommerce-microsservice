#!/usr/bin/env bash
# Borda HTTPS da VM de produção (Oracle Linux 9): nginx, certificado Let's Encrypt e SELinux.
# Idempotente: pode ser executado de novo a qualquer momento.
# Pré-requisitos: vm-bootstrap.sh aplicado, DNS de loja/auth apontando para a VM e porta 80 aberta na OCI.
# Uso na VM: sudo ./edge-setup.sh   (ecommerce.conf precisa estar na mesma pasta: scripts/prod/nginx/)
set -euo pipefail

[[ $EUID -eq 0 ]] || { echo 'Execute com sudo.' >&2; exit 1; }

DOMAINS=(loja.fabianoquirinos.com.br auth.fabianoquirinos.com.br)
CERT_NAME=${DOMAINS[0]}
WEBROOT=/var/www/certbot
NGINX_SITE=/etc/nginx/conf.d/ecommerce.conf
SITE_SOURCE="$(dirname "$(readlink -f "$0")")/nginx/ecommerce.conf"
[[ -f $SITE_SOURCE ]] || { echo "Não encontrado: $SITE_SOURCE" >&2; exit 1; }

echo '==> nginx (AppStream) e firewall: só HTTP e HTTPS'
dnf -y install nginx
firewall-cmd --permanent --add-service=http --add-service=https
firewall-cmd --reload

echo '==> Webroot do desafio ACME (HTTP-01)'
install -d -o root -g root -m 0755 "$WEBROOT"
restorecon -R "$WEBROOT"

echo '==> nginx.conf: comenta o server padrão do pacote (página de boas-vindas e [::]:80)'
# Comenta do "    server {" do http até o "    }" correspondente; linhas já comentadas não casam de novo.
if grep -qE '^    server \{' /etc/nginx/nginx.conf; then
  awk '!done && /^    server \{/ { inblock = 1 }
       inblock { print "#" $0; if (/^    \}/) { inblock = 0; done = 1 }; next }
       { print }' /etc/nginx/nginx.conf > /etc/nginx/nginx.conf.new
  mv /etc/nginx/nginx.conf.new /etc/nginx/nginx.conf
  restorecon /etc/nginx/nginx.conf
fi

echo '==> SELinux: nginx pode conectar só em portas web (não em banco, Redis ou broker)'
setsebool -P httpd_can_network_relay on
# 8080 (gateway) já é http_cache_port_t na política; 8180 (Keycloak) precisa de rótulo.
semanage port -a -t http_port_t -p tcp 8180 2>/dev/null || semanage port -m -t http_port_t -p tcp 8180

echo '==> Certbot via EPEL, restrito aos pacotes dele'
dnf -y install oracle-epel-release-el9 dnf-plugins-core
dnf config-manager --save \
  --setopt=ol9_developer_EPEL.enabled=1 \
  --setopt='ol9_developer_EPEL.includepkgs=certbot python3-certbot python3-acme python3-josepy python3-configargparse python3-parsedatetime python3-pyrfc3339'
dnf -y install certbot
# O pacote do EPEL não habilita a renovação automática.
systemctl enable --now certbot-renew.timer

if [[ ! -f /etc/letsencrypt/live/$CERT_NAME/fullchain.pem ]]; then
  echo '==> Certificado ausente: sobe só a porta 80 (ACME + 301) para emitir'
  cat > "$NGINX_SITE" <<EOF
server_tokens off;

server {
    listen 80;
    server_name ${DOMAINS[*]};

    location /.well-known/acme-challenge/ {
        root $WEBROOT;
    }

    location / {
        return 301 https://\$host\$request_uri;
    }
}
EOF
  restorecon "$NGINX_SITE"
  nginx -t
  systemctl enable nginx
  systemctl restart nginx

  domain_args=()
  for domain in "${DOMAINS[@]}"; do domain_args+=(-d "$domain"); done

  # Sem e-mail: o Let's Encrypt não envia mais avisos de expiração; o alerta fica no monitoramento.
  certbot certonly --webroot -w "$WEBROOT" --cert-name "$CERT_NAME" "${domain_args[@]}" \
    --deploy-hook 'systemctl reload nginx' \
    --agree-tos --register-unsafely-without-email --non-interactive
fi

echo '==> Configuração completa do nginx (loja, auth, TLS, default_server)'
install -o root -g root -m 0644 "$SITE_SOURCE" "$NGINX_SITE"
nginx -t
systemctl enable nginx
# restart (não reload) para fechar sockets de listen que deixaram de existir, como o [::]:80.
systemctl restart nginx

echo '==> Resultado'
certbot certificates --cert-name "$CERT_NAME" 2>/dev/null | grep -E 'Domains|Expiry'
systemctl list-timers certbot-renew.timer --no-pager | sed -n 2p
ss -ltn '( sport = :80 or sport = :443 )'
