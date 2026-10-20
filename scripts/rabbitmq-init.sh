#!/bin/sh
# Provisiona acesso por serviço sem gravar definições com senhas no repositório.
set -eu
: "${RABBITMQ_ADMIN_USERNAME:?defina RABBITMQ_ADMIN_USERNAME}"
: "${RABBITMQ_ADMIN_PASSWORD:?defina RABBITMQ_ADMIN_PASSWORD}"
: "${RABBITMQ_ORDER_PASSWORD:?defina RABBITMQ_ORDER_PASSWORD}"
: "${RABBITMQ_NOTIFICATION_PASSWORD:?defina RABBITMQ_NOTIFICATION_PASSWORD}"

api_url=${RABBITMQ_API_URL:-http://rabbitmq:15672}

# Escapa controles, aspas e barras sem depender de jq na imagem curl.
json_string() {
  printf '%s' "$1" | od -An -v -tu1 | LC_ALL=C awk '
    BEGIN { printf "\"" }
    { for (i = 1; i <= NF; i++) {
        if ($i == 34 || $i == 92) printf "\\%c", $i;
        else if ($i < 32) printf "\\u%04x", $i;
        else printf "%c", $i;
    } }
    END { printf "\"" }'
}

put() {
  # Descarta respostas para não registrar dados de usuários ou erros com payloads.
  curl --fail --silent --show-error --output /dev/null \
    --connect-timeout 5 --max-time 15 \
    --retry 10 --retry-delay 2 --retry-max-time 60 --retry-connrefused \
    --user "$RABBITMQ_ADMIN_USERNAME:$RABBITMQ_ADMIN_PASSWORD" \
    --request PUT --header 'Content-Type: application/json' \
    --data-binary @- "$api_url/api/$1"
}

printf '{}' | put vhosts/ecommerce
admin_path=$(printf '%s' "$RABBITMQ_ADMIN_USERNAME" | od -An -v -tu1 | LC_ALL=C awk '{ for (i = 1; i <= NF; i++) printf "%%%02X", $i }')
printf '{"password":%s,"tags":"administrator"}' "$(json_string "$RABBITMQ_ADMIN_PASSWORD")" | put "users/$admin_path"
# O bootstrap concede acesso AMQP amplo; o administrador deve operar só pelo console/API.
printf '%s' '{"configure":"(?!)","write":"(?!)","read":"(?!)"}' | put "permissions/ecommerce/$admin_path"

for service in order-service notification-service; do
  case "$service" in
    order-service) password=$RABBITMQ_ORDER_PASSWORD ;;
    notification-service) password=$RABBITMQ_NOTIFICATION_PASSWORD ;;
  esac
  printf '{"password":%s,"tags":""}' "$(json_string "$password")" | put "users/$service"
  # Ambos ainda declaram a topologia; apertar permissões quando a declaração duplicada for removida.
  printf '%s' '{"configure":"^order\\.","write":"^order\\.","read":"^order\\."}' | put "permissions/ecommerce/$service"
done
