#!/usr/bin/env bash
# Cria no OCI Vault um segredo com senha aleatória alfanumérica, sem exibi-la nem passá-la em argv.
# Alfanumérico: entra em URI de conexão sem escape e atende as regras de senha do Autonomous Database
# (12-30 caracteres com maiúscula, minúscula e dígito). Não sobrescreve segredo existente (rotação é outro fluxo).
# Uso na máquina de administração (OCI CLI com usuário que pode gerenciar segredos):
#   ./scripts/prod/vault-random-secret.sh <nome-do-segredo> <OCID-do-vault> <OCID-da-chave> [tamanho, padrão 30]
set -euo pipefail
umask 077

[[ $# -ge 3 && $# -le 4 ]] || { echo "Uso: $0 <nome> <vault-ocid> <key-ocid> [tamanho]" >&2; exit 1; }
secret_name=$1 vault_id=$2 key_id=$3 length=${4:-30}
if [[ ! $length =~ ^[0-9]+$ ]] || (( length < 12 || length > 30 )); then
  echo 'Tamanho deve ser 12-30.' >&2; exit 1
fi

compartment_id=$(oci kms management vault get --vault-id "$vault_id" --query 'data."compartment-id"' --raw-output)

# shellcheck disable=SC2016 # crases são literais do JMESPath
existing=$(oci vault secret list -c "$compartment_id" --vault-id "$vault_id" --name "$secret_name" --all \
  --query 'length(data[?"lifecycle-state"!=`DELETED`])' --raw-output)
# Com lista vazia o OCI CLI não imprime nada (nem 0); erro de chamada já abortou via set -e.
existing=${existing:-0}
[[ $existing =~ ^[0-9]+$ ]] || { echo "Resposta inesperada ao listar segredos: $existing" >&2; exit 1; }
(( existing == 0 )) || { echo "Segredo já existe: $secret_name (nada a fazer)." >&2; exit 0; }

# O payload com a senha só existe num arquivo 600 temporário, apagado ao sair.
payload=$(mktemp)
trap 'shred -u "$payload" 2>/dev/null || rm -f "$payload"' EXIT

python3 -I - "$payload" "$compartment_id" "$secret_name" "$vault_id" "$key_id" "$length" <<'PY'
import base64, json, secrets, string, sys
path, compartment_id, name, vault_id, key_id, length = sys.argv[1:]
alphabet = string.ascii_letters + string.digits
while True:
    pw = ''.join(secrets.choice(alphabet) for _ in range(int(length)))
    if any(c.isupper() for c in pw) and any(c.islower() for c in pw) and any(c.isdigit() for c in pw):
        break
with open(path, 'w') as f:
    json.dump({
        'compartmentId': compartment_id,
        'secretName': name,
        'vaultId': vault_id,
        'keyId': key_id,
        'secretContentContent': base64.b64encode(pw.encode()).decode(),
    }, f)
PY

oci vault secret create-base64 --from-json "file://$payload" \
  --query 'data.{nome:"secret-name",estado:"lifecycle-state"}' --output table
