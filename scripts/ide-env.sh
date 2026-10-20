#!/usr/bin/env bash
# Imprime, por serviço, as variáveis de ambiente para rodar os microsserviços
# pela IDE, no formato do campo "Environment variables" do IntelliJ
# (VAR=valor;VAR=valor). Só os segredos: os endereços já têm default em
# localhost nos application.yml.
#
# Uso: ./scripts/ide-env.sh [arquivo-env]   (padrão: .env na raiz)
#
# A saída contém segredos: rode no seu terminal, não cole em chats nem em logs.

set -euo pipefail

env_file="${1:-$(dirname "$0")/../.env}"
[[ -f "$env_file" ]] || { echo "Arquivo não encontrado: $env_file" >&2; exit 1; }

# Lê uma variável do .env sem executar o arquivo (source rodaria qualquer comando nele).
get() {
  local value
  value=$(grep -E "^$1=" "$env_file" | tail -n1 | cut -d= -f2-) \
    || { echo "Variável ausente no $env_file: $1" >&2; exit 1; }
  value="${value%\"}"; value="${value#\"}"
  printf '%s' "$value"
}

# serviço  →  pares VAR_NA_IDE:VAR_NO_.env
# Em dev, order e inventory entram no Postgres local com o usuário postgres,
# cuja senha é a DB_PASSWORD (POSTGRES_PASSWORD do container).
declare -A services=(
  [inventory-service]="DB_PASSWORD:DB_PASSWORD"
  [order-service]="DB_PASSWORD:DB_PASSWORD KC_ORDER_SERVICE_CLIENT_SECRET:KC_ORDER_SERVICE_CLIENT_SECRET SPRING_RABBITMQ_USERNAME:RABBITMQ_USERNAME SPRING_RABBITMQ_PASSWORD:RABBITMQ_PASSWORD"
  [notification-service]="SPRING_RABBITMQ_USERNAME:RABBITMQ_USERNAME SPRING_RABBITMQ_PASSWORD:RABBITMQ_PASSWORD"
  [api-gateway]="KC_BFF_CLIENT_SECRET:KC_BFF_CLIENT_SECRET REDIS_PASSWORD:REDIS_PASSWORD"
)

for service in inventory-service order-service notification-service api-gateway; do
  line=""
  for pair in ${services[$service]}; do
    line+="${pair%%:*}=$(get "${pair##*:}");"
  done
  printf '\n# %s\n%s\n' "$service" "${line%;}"
done
printf '\n# eureka-server e product-service: nenhuma variável necessária.\n'
