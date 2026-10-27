#!/usr/bin/env bash
set +xv
set -euo pipefail
umask 077

readonly SECRET_NAMES=(
  KEYCLOAK_ADMIN_PASSWORD
  KEYCLOAK_DB_PASSWORD
  KC_BFF_CLIENT_SECRET
  KC_ORDER_SERVICE_CLIENT_SECRET
  KC_SMTP_USER
  KC_SMTP_PASSWORD
  ORDER_DB_PASSWORD
  INVENTORY_DB_PASSWORD
  RABBITMQ_ADMIN_PASSWORD
  RABBITMQ_ORDER_PASSWORD
  RABBITMQ_NOTIFICATION_PASSWORD
  RABBITMQ_INVENTORY_PASSWORD
  REDIS_PASSWORD
  GRAFANA_ADMIN_PASSWORD
  PRODUCT_MONGODB_PASSWORD
)

fail() {
  printf '==> %s\n' "$1" >&2
  exit 1
}

fetch_secret() {
  if [[ -n ${OCI_SECRET_CMD:-} ]]; then
    "$OCI_SECRET_CMD" "$1"
  else
    oci secrets secret-bundle get-secret-bundle-by-name \
      --vault-id "$VAULT_ID" --secret-name "$1" \
      --query 'data."secret-bundle-content".content' --raw-output | base64 -d
  fi
} 2>/dev/null

validate_secret() {
  local name=$1 value=$2
  [[ -n $value && $value != *$'\n'* && $value != *$'\r'* && $value != *"'"* ]] \
    || fail "Segredo inválido: $name"
  case "$name" in
    PRODUCT_MONGODB_PASSWORD)
      [[ $value =~ ^[A-Za-z0-9._~-]+$ ]] || fail "Segredo inválido: $name"
      ;;
  esac
}

write_env() {
  local template=$1 mongodb_username=$2 mongodb_host=$3 name mongodb_uri
  local -n values=$4

  printf '# gerado por scripts/prod/generate-prod-env.sh em %s, não editar\n' \
    "$(date +%Y-%m-%dT%H:%M:%S%z)"
  cat -- "$template"
  printf '\n# Segredos do OCI Vault\n'
  for name in "${SECRET_NAMES[@]}"; do
    printf "%s='%s'\n" "$name" "${values[$name]}"
  done

  # shellcheck disable=SC2016
  printf -v mongodb_uri \
    'mongodb://%s:%s@%s:27017/%s?authMechanism=PLAIN&authSource=$external&ssl=true&retryWrites=false&loadBalanced=true' \
    "$mongodb_username" "${values[PRODUCT_MONGODB_PASSWORD]}" "$mongodb_host" "$mongodb_username"
  printf "PRODUCT_MONGODB_URI='%s'\n" "$mongodb_uri"
}

main() {
  [[ $EUID -eq 0 ]] || fail 'Execute com sudo.'
  [[ $# -le 1 ]] || fail 'Uso: sudo ./scripts/prod/generate-prod-env.sh <OCID-do-vault>'
  VAULT_ID=${1:-${VAULT_ID:-}}
  [[ -n $VAULT_ID ]] || fail 'Informe o OCID do Vault como argumento ou via VAULT_ID.'
  export OCI_CLI_AUTH=instance_principal
  export LC_ALL=C

  local root_dir env_file template name value secret backup_file
  local mongodb_host='' mongodb_username=''
  local -A secret_values=()
  root_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
  env_file="$root_dir/.env"
  template="$root_dir/scripts/prod/prod.env"
  [[ -f $template ]] || fail 'scripts/prod/prod.env não encontrado.'
  [[ ! -L $env_file && ( ! -e $env_file || -f $env_file ) ]] \
    || fail '.env deve ser um arquivo regular, sem link simbólico.'

  if [[ -n ${OCI_SECRET_CMD:-} ]]; then
    [[ -f $OCI_SECRET_CMD && -x $OCI_SECRET_CMD ]] || fail 'OCI_SECRET_CMD deve apontar para um executável.'
  else
    command -v oci >/dev/null 2>&1 || fail 'OCI CLI não encontrado.'
  fi

  while IFS='=' read -r name value || [[ -n $name ]]; do
    case "$name" in
      PRODUCT_MONGODB_HOST) mongodb_host=$value ;;
      PRODUCT_MONGODB_USERNAME) mongodb_username=$value ;;
    esac
  done < "$template"
  [[ -n $mongodb_host && -n $mongodb_username ]] || fail 'Configuração pública do MongoDB incompleta.'

  temp_file=$(mktemp "$root_dir/.env.tmp-XXXXXXXX")
  trap 'rm -f -- "$temp_file"' EXIT
  trap 'exit 1' HUP INT TERM
  chmod 600 "$temp_file"
  chown 0:0 "$temp_file"

  echo '==> Lendo segredos do OCI Vault'
  for name in "${SECRET_NAMES[@]}"; do
    if ! secret=$(fetch_secret "$name" && printf '.'); then
      fail "Falha ao ler segredo: $name"
    fi
    secret=${secret%.}
    validate_secret "$name" "$secret"
    # shellcheck disable=SC2034
    secret_values[$name]=$secret
  done
  write_env "$template" "$mongodb_username" "$mongodb_host" secret_values > "$temp_file"

  if command -v docker >/dev/null 2>&1; then
    docker compose --project-directory "$root_dir" --env-file "$temp_file" config --quiet >/dev/null 2>&1 \
      || fail 'Falha ao validar .env com Docker Compose.'
  fi

  [[ ! -L $env_file && ( ! -e $env_file || -f $env_file ) ]] \
    || fail '.env deve ser um arquivo regular, sem link simbólico.'
  if [[ -e $env_file ]]; then
    backup_file=$(mktemp "$env_file.bak-$(date +%Y%m%dT%H%M%S)-XXXXXXXX")
    cp -- "$env_file" "$backup_file"
    chmod 600 "$backup_file"
    chown 0:0 "$backup_file"
    echo "==> Backup criado: ${backup_file##*/}"
  fi
  mv -fT -- "$temp_file" "$env_file"
  printf '==> .env gerado (%s segredos), nenhum valor impresso\n' "${#SECRET_NAMES[@]}"
}

main "$@"
