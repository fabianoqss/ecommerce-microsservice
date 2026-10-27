#!/usr/bin/env bash
set -euo pipefail
umask 077

usage() {
  echo "Uso: sudo $0 <OCID-do-vault> (ou defina VAULT_ID)" >&2
  exit 1
}

[[ $EUID -eq 0 ]] || { echo 'Execute com sudo.' >&2; exit 1; }

[[ $# -le 1 ]] || usage
VAULT_ID="${1:-${VAULT_ID:-}}"
if [[ -z "${OCI_SECRET_CMD:-}" && -z "$VAULT_ID" ]]; then
  usage
fi

script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
ca_cert="${DB_BOOTSTRAP_CA_FILE:-$script_dir/certs/oci-postgresql-ca.pem}"

pg_host="${PGHOST:-primary.7wlyv3wm7ptz2osd4hjet74onx33dq.postgresql.sa-saopaulo-1.oci.oraclecloud.com}"
pg_port="${PGPORT:-5432}"
pg_user="${PGUSER:-admin}"
pg_database="${PGDATABASE:-postgres}"
ssl_mode="${DB_BOOTSTRAP_SSLMODE:-${PGSSLMODE:-verify-full}}"

docker_ssl_args=()
if [[ "$ssl_mode" != "disable" ]]; then
  [[ -f "$ca_cert" ]] || { echo "Certificado CA não encontrado: $ca_cert" >&2; exit 1; }
  docker_ssl_args=(-v "$ca_cert:/certs/oci-postgresql-ca.pem:ro" -e PGSSLROOTCERT=/certs/oci-postgresql-ca.pem)
fi

read_secret() {
  local secret_name="$1"
  local secret_val
  if [[ -n "${OCI_SECRET_CMD:-}" ]]; then
    secret_val=$("$OCI_SECRET_CMD" "$secret_name")
  else
    export OCI_CLI_AUTH=instance_principal
    secret_val=$(oci secrets secret-bundle get-secret-bundle-by-name \
      --vault-id "$VAULT_ID" \
      --secret-name "$secret_name" \
      --query 'data."secret-bundle-content".content' \
      --raw-output | base64 -d)
  fi
  [[ -n "$secret_val" ]] || { echo "Segredo vazio ou não encontrado: $secret_name" >&2; exit 1; }
  printf '%s' "$secret_val"
}

echo '==> Lendo segredos do banco no OCI Vault'
admin_password=$(read_secret "prod-postgres-password")
order_db_password=$(read_secret "ORDER_DB_PASSWORD")
inventory_db_password=$(read_secret "INVENTORY_DB_PASSWORD")
keycloak_db_password=$(read_secret "KEYCLOAK_DB_PASSWORD")

echo "==> Conectando em $pg_host:$pg_port (sslmode=$ssl_mode) e reconciliando roles e bancos"
PGPASSWORD="$admin_password" \
ORDER_DB_PASSWORD="$order_db_password" \
INVENTORY_DB_PASSWORD="$inventory_db_password" \
KEYCLOAK_DB_PASSWORD="$keycloak_db_password" \
docker run --rm -i --network host \
  "${docker_ssl_args[@]}" \
  -e PGHOST="$pg_host" \
  -e PGPORT="$pg_port" \
  -e PGUSER="$pg_user" \
  -e PGDATABASE="$pg_database" \
  -e PGSSLMODE="$ssl_mode" \
  -e PGPASSWORD \
  -e ORDER_DB_PASSWORD \
  -e INVENTORY_DB_PASSWORD \
  -e KEYCLOAK_DB_PASSWORD \
  postgres:17-alpine \
  psql -X -q -v ON_ERROR_STOP=1 <<-'EOSQL'
	\getenv order_db_password ORDER_DB_PASSWORD
	\getenv inventory_db_password INVENTORY_DB_PASSWORD
	\getenv keycloak_db_password KEYCLOAK_DB_PASSWORD

	SELECT 'CREATE ROLE order_service WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
	WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'order_service') \gexec
	ALTER ROLE order_service WITH LOGIN NOCREATEDB NOCREATEROLE PASSWORD :'order_db_password';

	SELECT 'CREATE ROLE inventory_service WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
	WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'inventory_service') \gexec
	ALTER ROLE inventory_service WITH LOGIN NOCREATEDB NOCREATEROLE PASSWORD :'inventory_db_password';

	SELECT 'CREATE ROLE keycloak WITH LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
	WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_roles WHERE rolname = 'keycloak') \gexec
	ALTER ROLE keycloak WITH LOGIN NOCREATEDB NOCREATEROLE PASSWORD :'keycloak_db_password';

	GRANT order_service TO CURRENT_USER WITH INHERIT FALSE, SET TRUE;
	GRANT inventory_service TO CURRENT_USER WITH INHERIT FALSE, SET TRUE;
	GRANT keycloak TO CURRENT_USER WITH INHERIT FALSE, SET TRUE;

	SELECT 'CREATE DATABASE "order-service" OWNER order_service'
	WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_database WHERE datname = 'order-service') \gexec
	ALTER DATABASE "order-service" OWNER TO order_service;
	SET ROLE order_service;
	REVOKE ALL ON DATABASE "order-service" FROM PUBLIC;
	GRANT CONNECT ON DATABASE "order-service" TO order_service;
	RESET ROLE;

	SELECT 'CREATE DATABASE inventory_service OWNER inventory_service'
	WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_database WHERE datname = 'inventory_service') \gexec
	ALTER DATABASE inventory_service OWNER TO inventory_service;
	SET ROLE inventory_service;
	REVOKE ALL ON DATABASE inventory_service FROM PUBLIC;
	GRANT CONNECT ON DATABASE inventory_service TO inventory_service;
	RESET ROLE;

	SELECT 'CREATE DATABASE keycloak OWNER keycloak'
	WHERE NOT EXISTS (SELECT 1 FROM pg_catalog.pg_database WHERE datname = 'keycloak') \gexec
	ALTER DATABASE keycloak OWNER TO keycloak;
	SET ROLE keycloak;
	REVOKE ALL ON DATABASE keycloak FROM PUBLIC;
	GRANT CONNECT ON DATABASE keycloak TO keycloak;
	RESET ROLE;

	\echo '==> Resumo dos bancos provisionados (banco -> dono)'
	\pset tuples_only on
	\pset format unaligned
	SELECT datname || ' -> ' || pg_catalog.pg_get_userbyid(datdba)
	FROM pg_catalog.pg_database
	WHERE datname IN ('order-service', 'inventory_service', 'keycloak')
	ORDER BY datname;
EOSQL
