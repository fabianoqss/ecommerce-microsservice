#!/bin/bash
# Executado pelo entrypoint do Postgres apenas na criação do volume (só dev).
set -euo pipefail
: "${KEYCLOAK_DB_PASSWORD:?defina KEYCLOAK_DB_PASSWORD}"

psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
     -v kc_password="$KEYCLOAK_DB_PASSWORD" <<-'EOSQL'
	CREATE DATABASE "order-service";
	CREATE DATABASE inventory_service;
	CREATE USER keycloak WITH PASSWORD :'kc_password';
	CREATE DATABASE keycloak OWNER keycloak;
EOSQL
