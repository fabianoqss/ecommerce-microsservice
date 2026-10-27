#!/usr/bin/env bash
set -euo pipefail

env_file="${1:-$(dirname "$0")/../.env}"
[[ -f "$env_file" ]] || { echo "Arquivo não encontrado: $env_file" >&2; exit 1; }

get() {
  local value
  value=$(awk -v key="$1" 'index($0, key "=") == 1 { value = substr($0, length(key) + 2); found = 1 } END { if (!found) exit 1; print value }' "$env_file") \
    || { echo "Variável ausente no $env_file: $1" >&2; exit 1; }
  value="${value%$'\r'}"
  if [[ "$value" == \"*\" || "$value" == \'*\' ]]; then
    value="${value:1:${#value}-2}"
  fi
  [[ -n "$value" && "$value" != *';'* && "$value" != *$'\n'* ]] \
    || { echo "Valor vazio ou incompatível com o IntelliJ: $1" >&2; exit 1; }
  printf '%s' "$value"
}

sampling=$(get TRACING_SAMPLING_PROBABILITY)

declare -A services=(
  [product-service]=""
  [inventory-service]="DB_PASSWORD:DB_PASSWORD SPRING_RABBITMQ_PASSWORD:RABBITMQ_INVENTORY_PASSWORD"
  [order-service]="DB_PASSWORD:DB_PASSWORD KC_ORDER_SERVICE_CLIENT_SECRET:KC_ORDER_SERVICE_CLIENT_SECRET SPRING_RABBITMQ_PASSWORD:RABBITMQ_ORDER_PASSWORD"
  [notification-service]="SPRING_RABBITMQ_PASSWORD:RABBITMQ_NOTIFICATION_PASSWORD REDIS_PASSWORD:REDIS_PASSWORD"
  [api-gateway]="KC_BFF_CLIENT_SECRET:KC_BFF_CLIENT_SECRET REDIS_PASSWORD:REDIS_PASSWORD"
)

declare -A lines
for service in product-service inventory-service order-service notification-service api-gateway; do
  line="TRACING_SAMPLING_PROBABILITY=$sampling;"
  case "$service" in
    order-service|notification-service|inventory-service)
      line+="SPRING_RABBITMQ_USERNAME=$service;SPRING_RABBITMQ_VIRTUAL_HOST=ecommerce;" ;;
  esac
  [[ "$service" != api-gateway ]] || line+='SESSION_COOKIE_SECURE=false;'
  for pair in ${services[$service]}; do
    line+="${pair%%:*}=$(get "${pair##*:}");"
  done
  lines[$service]="${line%;}"
done
for service in product-service inventory-service order-service notification-service api-gateway; do
  printf '\n# %s\n%s\n' "$service" "${lines[$service]}"
done
