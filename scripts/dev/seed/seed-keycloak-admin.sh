#!/bin/sh
set -eu
umask 077

: "${KEYCLOAK_ADMIN_PASSWORD:?defina KEYCLOAK_ADMIN_PASSWORD}"
: "${KC_ADMIN_USER_EMAIL:?defina KC_ADMIN_USER_EMAIL}"
: "${KC_ADMIN_USER_PASSWORD:?defina KC_ADMIN_USER_PASSWORD}"

keycloak_url=${KEYCLOAK_URL:-http://keycloak:8080}
admin_user=${KEYCLOAK_ADMIN_USER:-admin}
realm=${KEYCLOAK_REALM:-ecommerce}
kcadm=${KCADM_BIN:-/opt/keycloak/bin/kcadm.sh}

tmp_dir=$(mktemp -d)
trap 'rm -rf -- "$tmp_dir"' EXIT
config_file="$tmp_dir/kcadm.config"

"$kcadm" config credentials \
  --config "$config_file" \
  --server "$keycloak_url" \
  --realm master \
  --user "$admin_user" \
  --password "$KEYCLOAK_ADMIN_PASSWORD"

user_id=$("$kcadm" get users \
  --config "$config_file" \
  -r "$realm" \
  -q "username=$KC_ADMIN_USER_EMAIL" \
  -q exact=true \
  --fields id \
  --format csv \
  --noquotes | tr -d '"\r[:space:]')

if [ -z "$user_id" ]; then
  user_id=$("$kcadm" create users \
    --config "$config_file" \
    -r "$realm" \
    -s "username=$KC_ADMIN_USER_EMAIL" \
    -s "email=$KC_ADMIN_USER_EMAIL" \
    -s enabled=true \
    -s emailVerified=true \
    -s 'requiredActions=[]' \
    -i | tr -d '"\r[:space:]')
else
  "$kcadm" update "users/$user_id" \
    --config "$config_file" \
    -r "$realm" \
    -s "email=$KC_ADMIN_USER_EMAIL" \
    -s enabled=true \
    -s emailVerified=true \
    -s 'requiredActions=[]'
  for cred_id in $("$kcadm" get "users/$user_id/credentials" \
    --config "$config_file" \
    -r "$realm" \
    --fields id,type \
    --format csv \
    --noquotes | tr -d '"\r' | grep ',password$' | cut -d, -f1); do
    "$kcadm" delete "users/$user_id/credentials/$cred_id" \
      --config "$config_file" \
      -r "$realm"
  done
fi

"$kcadm" set-password \
  --config "$config_file" \
  -r "$realm" \
  --username "$KC_ADMIN_USER_EMAIL" \
  --new-password "$KC_ADMIN_USER_PASSWORD"

roles=$("$kcadm" get-roles \
  --config "$config_file" \
  -r "$realm" \
  --uusername "$KC_ADMIN_USER_EMAIL" \
  --fields name \
  --format csv \
  --noquotes | tr -d '"\r')

if ! printf '%s\n' "$roles" | grep -qx 'ADMIN'; then
  "$kcadm" add-roles \
    --config "$config_file" \
    -r "$realm" \
    --uusername "$KC_ADMIN_USER_EMAIL" \
    --rolename ADMIN
fi

echo "seed keycloak-admin: usuário $KC_ADMIN_USER_EMAIL configurado com role ADMIN no realm $realm"
