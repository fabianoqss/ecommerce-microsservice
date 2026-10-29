#!/usr/bin/env bash
set +xv
set -euo pipefail
umask 077
export LC_ALL=C

readonly APPS=(product-service inventory-service notification-service order-service api-gateway)
temp_file=''
committed=0
completed=0
previous_tag=''

fail() {
  printf '==> %s\n' "$1" >&2
  exit 1
}

valid_tag() {
  [[ $1 =~ ^[0-9a-f]{40}$ ]]
}

check_env() {
  [[ -f $env_file && ! -L $env_file ]]
}

compose() {
  local tag=$1
  shift
  IMAGE_TAG="$tag" "$docker_bin" compose --project-directory "$root_dir" "$@"
}

read_previous_tag() {
  local line value found=0
  while IFS= read -r line || [[ -n $line ]]; do
    [[ $line == IMAGE_TAG=* ]] || continue
    [[ $found -eq 0 ]] || fail 'IMAGE_TAG duplicada no .env.'
    found=1
    value=${line#IMAGE_TAG=}
    if [[ $value == \"*\" || $value == \'*\' ]]; then
      value=${value:1:${#value}-2}
    fi
    valid_tag "$value" || fail 'IMAGE_TAG atual inválida no .env.'
    previous_tag=$value
  done < "$env_file"
}

write_env_lines() {
  local tag=$1 line newline=1 found=0
  while :; do
    line=''
    if IFS= read -r line; then
      newline=1
    else
      [[ -n $line ]] || break
      newline=0
    fi
    if [[ $line == IMAGE_TAG=* ]]; then
      line="IMAGE_TAG=$tag"
      found=1
    fi
    printf '%s' "$line" || return 1
    if [[ $newline -eq 1 ]]; then
      printf '\n' || return 1
    fi
    [[ $newline -eq 1 ]] || break
  done < "$env_file"
  if [[ $found -eq 0 ]]; then
    if [[ $newline -eq 0 ]]; then
      printf '\n' || return 1
    fi
    printf 'IMAGE_TAG=%s\n' "$tag" || return 1
  fi
  return 0
}

write_tag() {
  local tag=$1 was_committed=$committed
  check_env || return 1
  temp_file=$(mktemp "$root_dir/.env.tmp-XXXXXXXX") || return 1
  chmod 600 "$temp_file" || return 1
  chown 0:0 "$temp_file" || return 1
  write_env_lines "$tag" > "$temp_file" || return 1
  compose "$tag" --env-file "$temp_file" config --quiet >/dev/null 2>&1 || return 1
  check_env || return 1
  committed=1
  if ! mv -fT -- "$temp_file" "$env_file"; then
    committed=$was_committed
    return 1
  fi
  temp_file=''
}

cleanup_temp() {
  if [[ -n $temp_file ]]; then
    rm -f -- "$temp_file"
    temp_file=''
  fi
}

print_digests() {
  local tag=$1 images service image reference digest
  images=$(compose "$tag" config --images "${APPS[@]}" 2>/dev/null) || return 1
  for service in "${APPS[@]}"; do
    reference=''
    while IFS= read -r image; do
      if [[ $image == */ecommerce-"$service":"$tag" ]]; then
        reference=$image
      fi
    done <<< "$images"
    [[ -n $reference ]] || return 1
    digest=$("$docker_bin" image inspect --format '{{index .RepoDigests 0}}' "$reference" 2>/dev/null) \
      || return 1
    [[ $digest =~ ^[^[:space:]]+@sha256:[0-9a-f]{64}$ ]] || return 1
    printf '==> %s: %s\n' "$service" "$digest"
  done
}

up_apps() {
  compose "$1" up -d --no-build --remove-orphans --no-deps "${APPS[@]}" >/dev/null 2>&1
}

wait_healthy() {
  local tag=$1 deadline=$((SECONDS + health_timeout)) service containers container state
  local ready fatal remaining pause
  local -a pending failed
  while :; do
    pending=()
    failed=()
    for service in "${APPS[@]}"; do
      ready=1
      fatal=0
      if ! containers=$(compose "$tag" ps --all -q "$service" 2>/dev/null); then
        containers=''
        fatal=1
      fi
      [[ -n $containers ]] || ready=0
      while IFS= read -r container; do
        [[ -n $container ]] || continue
        if ! state=$("$docker_bin" inspect --format \
          '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}missing{{end}}' \
          "$container" 2>/dev/null); then
          state='inspect-failed'
          fatal=1
        fi
        [[ $state == 'running healthy' ]] || ready=0
        case "$state" in
          *' unhealthy'|exited\ *|dead\ *|removing\ *) fatal=1 ;;
        esac
      done <<< "$containers"
      [[ $ready -eq 1 ]] || pending+=("$service")
      [[ $fatal -eq 0 ]] || failed+=("$service")
    done
    if [[ ${#failed[@]} -gt 0 ]]; then
      printf '==> Serviços com falha: %s\n' "${failed[*]}" >&2
      return 1
    fi
    [[ ${#pending[@]} -gt 0 ]] || return 0
    remaining=$((deadline - SECONDS))
    if [[ $remaining -le 0 ]]; then
      printf '==> Timeout aguardando running+healthy: %s\n' "${pending[*]}" >&2
      return 1
    fi
    pause=$health_interval
    [[ $pause -le $remaining ]] || pause=$remaining
    sleep "$pause" || return 1
  done
}

on_exit() {
  local status=$?
  trap - EXIT
  trap '' HUP INT TERM
  cleanup_temp
  if [[ $committed -eq 1 && $completed -eq 0 ]]; then
    status=1
    if [[ -n $previous_tag ]]; then
      printf '==> Deploy falhou; iniciando rollback para %s.\n' "$previous_tag" >&2
      if write_tag "$previous_tag" && up_apps "$previous_tag" && wait_healthy "$previous_tag"; then
        printf '==> Rollback concluído; tag ativa: %s. O deploy falhou.\n' "$previous_tag" >&2
      else
        printf '==> Rollback também falhou; verifique os apps e a tag no .env.\n' >&2
      fi
    else
      printf '==> Deploy falhou sem rollback: não existe tag anterior.\n' >&2
    fi
  fi
  cleanup_temp
  exit "$status"
}

main() {
  [[ $EUID -eq 0 ]] || fail 'Execute com sudo.'
  [[ $# -eq 1 ]] || fail 'Uso: sudo ./scripts/prod/deploy.sh <sha-de-40-hex>'
  local new_tag=$1
  valid_tag "$new_tag" || fail 'Tag inválida: informe exatamente 40 caracteres hexadecimais minúsculos.'

  local lock_file=${DEPLOY_LOCK_FILE:-/run/lock/ecommerce-deploy.lock}
  [[ ! -L $lock_file && ( ! -e $lock_file || -f $lock_file ) ]] \
    || fail 'Lock deve ser um arquivo regular, sem link simbólico.'
  exec {lock_fd}>> "$lock_file"
  flock -n "$lock_fd" || fail 'Outro deploy está em andamento.'

  root_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
  env_file="$root_dir/.env"
  check_env || fail '.env deve existir e ser um arquivo regular, sem link simbólico.'
  cd -- "$root_dir"
  docker_bin=${DOCKER_BIN:-docker}
  health_timeout=${DEPLOY_HEALTH_TIMEOUT:-300}
  health_interval=${DEPLOY_HEALTH_INTERVAL:-5}
  [[ $health_timeout =~ ^(0|[1-9][0-9]{0,5})$ ]] \
    || fail 'DEPLOY_HEALTH_TIMEOUT deve ser um inteiro entre 0 e 999999 segundos.'
  [[ $health_interval =~ ^(0|[1-9][0-9]{0,5})$ ]] \
    || fail 'DEPLOY_HEALTH_INTERVAL deve ser um inteiro entre 0 e 999999 segundos.'
  read_previous_tag
  if [[ -z $previous_tag ]]; then
    printf '==> Primeiro deploy: não haverá rollback automático.\n' >&2
  elif [[ $new_tag == "$previous_tag" ]]; then
    printf '==> A tag solicitada já está ativa; seguindo para reparar os apps.\n' >&2
  fi

  trap on_exit EXIT
  trap 'fail "Deploy interrompido."' HUP INT TERM
  printf '==> Baixando imagens para %s.\n' "$new_tag"
  compose "$new_tag" pull "${APPS[@]}" >/dev/null 2>&1 \
    || fail "Pull falhou; .env e containers não foram alterados. Diagnóstico: sudo IMAGE_TAG=$new_tag docker compose pull"
  print_digests "$new_tag" || fail 'Falha ao obter os digests; .env e containers não foram alterados.'
  write_tag "$new_tag" || fail 'Falha ao gravar e validar a nova tag no .env.'
  up_apps "$new_tag" || fail 'Falha ao subir os apps. Diagnóstico: sudo docker compose logs --tail 100 <serviço>'
  wait_healthy "$new_tag" || fail 'Os apps não ficaram saudáveis.'
  printf '==> Tag ativa: %s\n' "$new_tag"
  compose "$new_tag" ps "${APPS[@]}" 2>/dev/null || fail 'Falha ao listar os apps.'
  completed=1
}

main "$@"
