#!/usr/bin/env bash
set -euo pipefail
umask 077

force=false
case "${1:-}" in
  '') ;;
  --force) force=true ;;
  *) echo 'Uso: ./scripts/generate-env.sh [--force]' >&2; exit 1 ;;
esac
[[ $# -le 1 ]] || { echo 'Uso: ./scripts/generate-env.sh [--force]' >&2; exit 1; }

root_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
env_file="$root_dir/.env"
template="$root_dir/.env.example"
[[ -f "$template" ]] || { echo '.env.example não encontrado.' >&2; exit 1; }
[[ ! -L "$env_file" && ! -d "$env_file" ]] || { echo '.env deve ser um arquivo regular, sem link simbólico.' >&2; exit 1; }
if [[ -e "$env_file" && "$force" != true ]]; then
  echo '.env já existe. Use --force para criar um backup e substituí-lo.' >&2
  exit 1
fi

generate_policy_secret() {
  openssl rand 256 | od -An -v -tu1 | LC_ALL=C awk '
    function next_byte() {
      if (pos >= n) exit 1
      return bytes[++pos]
    }
    function pick(alphabet, len, limit, b) {
      limit = 256 - (256 % len)
      while ((b = next_byte()) >= limit) {}
      return substr(alphabet, (b % len) + 1, 1)
    }
    {
      for (i = 1; i <= NF; i++) bytes[++n] = $i
    }
    END {
      upper = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
      lower = "abcdefghijklmnopqrstuvwxyz"
      digits = "0123456789"
      special = "-_."
      all = upper lower digits special
      chars[1] = pick(upper, 26)
      chars[2] = pick(lower, 26)
      chars[3] = pick(digits, 10)
      chars[4] = pick(special, 3)
      for (i = 5; i <= 24; i++) chars[i] = pick(all, 65)
      for (i = 24; i >= 2; i--) {
        limit = 256 - (256 % i)
        while ((b = next_byte()) >= limit) {}
        j = (b % i) + 1
        tmp = chars[i]
        chars[i] = chars[j]
        chars[j] = tmp
      }
      for (i = 1; i <= 24; i++) printf "%s", chars[i]
      printf "\n"
    }'
}

temp_file=$(mktemp "$root_dir/.env.tmp-XXXXXXXX")
trap 'rm -f -- "$temp_file"' EXIT
while IFS= read -r line || [[ -n "$line" ]]; do
  if [[ "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*=troque-politica- ]]; then
    secret=$(generate_policy_secret)
    [[ "$secret" =~ ^[A-Za-z0-9._-]{24}$ && "$secret" =~ [A-Z] && "$secret" =~ [a-z] && "$secret" =~ [0-9] && "$secret" =~ [._-] ]] \
      || { echo 'Falha ao gerar segredo.' >&2; exit 1; }
    line="${line%%=*}=$secret"
  elif [[ "$line" =~ ^[A-Za-z_][A-Za-z0-9_]*=troque- ]]; then
    secret=$(openssl rand -hex 24)
    [[ "$secret" =~ ^[0-9a-f]{48}$ ]] || { echo 'Falha ao gerar segredo.' >&2; exit 1; }
    line="${line%%=*}=$secret"
  fi
  printf '%s\n' "$line" >> "$temp_file"
done < "$template"
chmod 600 "$temp_file"

if [[ "$force" == true ]]; then
  if [[ -e "$env_file" ]]; then
    backup_file=$(mktemp "$env_file.bak-$(date +%Y%m%dT%H%M%S)-XXXXXXXX")
    cp -- "$env_file" "$backup_file"
    chmod 600 "$backup_file"
    echo "Backup criado: ${backup_file##*/}"
  fi
  mv -- "$temp_file" "$env_file"
else
  ln -- "$temp_file" "$env_file"
fi
echo '.env criado com permissão 600. Nenhum segredo foi impresso.'
