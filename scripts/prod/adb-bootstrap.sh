#!/usr/bin/env bash
# Provisionamento idempotente do usuário do product-service no Autonomous JSON Database (API Mongo).
# A porta SQL*Net (1522) e o ORDS (443) ficam fechados: o SQL administrativo vai pela própria API Mongo
# (porta 27017, estágio $sql), autenticado como ADMIN. Senhas vêm do OCI Vault (instance principal).
# Uso na VM: sudo ./scripts/prod/adb-bootstrap.sh <OCID-do-vault> (ou variável VAULT_ID)
set -euo pipefail
umask 077

usage() {
  echo "Uso: sudo $0 <OCID-do-vault> (ou defina VAULT_ID)" >&2
  exit 1
}

[[ $EUID -eq 0 ]] || { echo 'Execute com sudo.' >&2; exit 1; }

[[ $# -le 1 ]] || usage
VAULT_ID="${1:-${VAULT_ID:-}}"
[[ -n "$VAULT_ID" ]] || usage

adb_host="${ADB_HOST:-ccnlguvp.adb.sa-saopaulo-1.oraclecloudapps.com}"
mongosh_image="${MONGOSH_IMAGE:-mongo:7.0}"

read_secret() {
  local secret_name="$1"
  local secret_val
  secret_val=$(OCI_CLI_AUTH=instance_principal oci secrets secret-bundle get-secret-bundle-by-name \
    --vault-id "$VAULT_ID" \
    --secret-name "$secret_name" \
    --query 'data."secret-bundle-content".content' \
    --raw-output | base64 -d)
  [[ -n "$secret_val" ]] || { echo "Segredo vazio ou não encontrado: $secret_name" >&2; exit 1; }
  printf '%s' "$secret_val"
}

echo '==> Lendo segredos do Autonomous no OCI Vault'
ADB_ADMIN_PASSWORD=$(read_secret "prod-mongoDB-password")
PRODUCT_MONGODB_PASSWORD=$(read_secret "PRODUCT_MONGODB_PASSWORD")
export ADB_ADMIN_PASSWORD PRODUCT_MONGODB_PASSWORD

echo "==> Conectando em $adb_host:27017 (TLS) e reconciliando o usuário PRODUCT_SERVICE"
# O JS vai como arquivo (por stdin o mongosh age como REPL e ecoa prompts); não contém segredos.
js_file=$(mktemp --suffix=.js)
chmod 0644 "$js_file"  # só código, sem segredos: legível pelo usuário do container
trap 'rm -f "$js_file"' EXIT
cat > "$js_file" <<-'EOJS'
	const host = process.env.ADB_HOST;
	const uri = (user, password) => 'mongodb://' + user + ':' + encodeURIComponent(password) + '@' + host +
	  ':27017/' + user + '?authMechanism=PLAIN&authSource=$external&tls=true&retryWrites=false&loadBalanced=true';

	// A senha é concatenada no DDL (CREATE USER não aceita bind), então só passa se for estritamente alfanumérica.
	const password = process.env.PRODUCT_MONGODB_PASSWORD;
	if (!/^[A-Za-z0-9]{12,30}$/.test(password) || !/[A-Z]/.test(password) || !/[a-z]/.test(password) ||
	    !/[0-9]/.test(password) || password.toUpperCase().includes('PRODUCT_SERVICE')) {
	  throw new Error('Segredo inválido: PRODUCT_MONGODB_PASSWORD');
	}

	// Mínimo privilégio: só o necessário para a API Mongo criar e usar coleções no próprio schema,
	// cota limitada (em vez de UNLIMITED TABLESPACE) e sem acesso a outros schemas.
	// ORDS habilitado com auto_rest_auth: a API Mongo do ADB exige o schema REST-enabled; AutoREST exige login.
	const admin = connect(uri('ADMIN', process.env.ADB_ADMIN_PASSWORD));
	admin.aggregate([{ $sql: `
	  DECLARE
	    n PLS_INTEGER;
	  BEGIN
	    SELECT COUNT(*) INTO n FROM dba_users WHERE username = 'PRODUCT_SERVICE';
	    IF n = 0 THEN
	      EXECUTE IMMEDIATE 'CREATE USER PRODUCT_SERVICE IDENTIFIED BY "${password}" QUOTA 5G ON DATA';
	    ELSE
	      EXECUTE IMMEDIATE 'ALTER USER PRODUCT_SERVICE IDENTIFIED BY "${password}" ACCOUNT UNLOCK';
	      EXECUTE IMMEDIATE 'ALTER USER PRODUCT_SERVICE QUOTA 5G ON DATA';
	    END IF;
	    EXECUTE IMMEDIATE 'GRANT CREATE SESSION, CREATE TABLE, CREATE VIEW, CREATE SEQUENCE, CREATE PROCEDURE, SODA_APP TO PRODUCT_SERVICE';
	    ORDS_ADMIN.ENABLE_SCHEMA(p_enabled => TRUE, p_schema => 'PRODUCT_SERVICE',
	      p_url_mapping_type => 'BASE_PATH', p_url_mapping_pattern => 'product_service', p_auto_rest_auth => TRUE);
	  END;` }]).toArray();

	print('==> Privilégios de PRODUCT_SERVICE');
	admin.aggregate([{ $sql: `
	  SELECT privilege AS grant_name FROM dba_sys_privs WHERE grantee = 'PRODUCT_SERVICE'
	  UNION ALL
	  SELECT granted_role FROM dba_role_privs WHERE grantee = 'PRODUCT_SERVICE'
	  ORDER BY 1` }]).forEach(r => print('  ' + (r.GRANT_NAME ?? r.grant_name)));

	// Teste de ponta a ponta com o próprio usuário: cria, lê e remove uma coleção descartável.
	const app = connect(uri('PRODUCT_SERVICE', password));
	const probe = app.getCollection('bootstrap_probe');
	probe.insertOne({ ok: true });
	if (probe.findOne({ ok: true }) === null) throw new Error('Teste de leitura falhou');
	probe.drop();
	print('==> PRODUCT_SERVICE autentica, escreve e lê pela API Mongo');
EOJS

# Senhas entram só por variáveis de ambiente herdadas (nunca em argv).
docker run --rm --network host \
  -v "$js_file:/bootstrap.js:ro" \
  -e ADB_HOST="$adb_host" \
  -e ADB_ADMIN_PASSWORD \
  -e PRODUCT_MONGODB_PASSWORD \
  "$mongosh_image" \
  mongosh --quiet --nodb --norc /bootstrap.js
