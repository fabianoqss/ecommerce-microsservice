#!/usr/bin/env bash
set +xv
set -euo pipefail
umask 077
export LC_ALL=C

fail() {
  printf '==> %s\n' "$1" >&2
  exit 1
}

valid_sha() {
  [[ $1 =~ ^[0-9a-f]{40}$ ]]
}

run_git() {
  env -i PATH=/usr/bin:/bin HOME="${HOME:-/root}" LC_ALL=C \
    GIT_TERMINAL_PROMPT=0 GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null \
    GIT_NO_REPLACE_OBJECTS=1 \
    "$git_bin" -C "$root_dir" -c core.hooksPath=/dev/null -c core.fsmonitor=false "$@"
}

main() {
  [[ $# -eq 1 ]] || fail 'Uso: sudo ./scripts/prod/release.sh <sha-de-40-hex>'
  local sha=$1
  valid_sha "$sha" || fail 'SHA inválido: informe exatamente 40 caracteres hexadecimais minúsculos.'
  [[ $EUID -eq 0 ]] || fail 'Execute com sudo.'

  root_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd) \
    || fail 'Falha ao localizar o repositório.'
  git_bin=${GIT_BIN:-git}
  local deploy_script=${DEPLOY_SCRIPT:-"$root_dir/scripts/prod/deploy.sh"}
  local changes object_type

  run_git fetch --quiet origin refs/heads/main:refs/remotes/origin/main >/dev/null 2>&1 \
    || fail 'Falha ao buscar a main no origin; checkout e deploy não foram executados.'
  run_git cat-file -e "$sha^{commit}" >/dev/null 2>&1 \
    || fail 'Commit inexistente ou SHA não corresponde a um commit.'
  object_type=$(run_git cat-file -t "$sha" 2>/dev/null) \
    || fail 'Falha ao verificar o tipo do objeto Git.'
  [[ $object_type == commit ]] || fail 'SHA não corresponde a um commit.'
  run_git merge-base --is-ancestor "$sha" refs/remotes/origin/main >/dev/null 2>&1 \
    || fail 'commit fora da main; release recusado.'

  changes=$(run_git status --porcelain --untracked-files=no 2>/dev/null) \
    || fail 'Falha ao verificar alterações locais.'
  [[ -z $changes ]] || fail 'Alterações locais em arquivos versionados; release recusado.'

  run_git checkout --quiet -B main "$sha" >/dev/null 2>&1 \
    || fail 'Falha ao posicionar o repositório no commit solicitado.'
  printf '==> Repositório em %s\n' "$sha"
  exec "$deploy_script" "$sha"
}

main "$@"
