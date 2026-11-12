#!/usr/bin/env bash
set +xv
set -euo pipefail
umask 077
export LC_ALL=C

readonly APPS=(product-service inventory-service notification-service order-service api-gateway frontend)
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
    [[ $found -eq 0 ]] || fail 'Duplicate IMAGE_TAG in .env.'
    found=1
    value=${line#IMAGE_TAG=}
    if [[ $value == \"*\" || $value == \'*\' ]]; then
      value=${value:1:${#value}-2}
    fi
    valid_tag "$value" || fail 'Invalid current IMAGE_TAG in .env.'
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
      printf '==> Failed services: %s\n' "${failed[*]}" >&2
      return 1
    fi
    [[ ${#pending[@]} -gt 0 ]] || return 0
    remaining=$((deadline - SECONDS))
    if [[ $remaining -le 0 ]]; then
      printf '==> Timeout waiting for running+healthy: %s\n' "${pending[*]}" >&2
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
      printf '==> Deployment failed; starting rollback to %s.\n' "$previous_tag" >&2
      if write_tag "$previous_tag" && up_apps "$previous_tag" && wait_healthy "$previous_tag"; then
        printf '==> Rollback completed; active tag: %s. Deployment failed.\n' "$previous_tag" >&2
      else
        printf '==> Rollback also failed; check the apps and the tag in .env.\n' >&2
      fi
    else
      printf '==> Deployment failed without rollback: no previous tag exists.\n' >&2
    fi
  fi
  cleanup_temp
  exit "$status"
}

main() {
  [[ $EUID -eq 0 ]] || fail 'Run with sudo.'
  [[ $# -eq 1 ]] || fail 'Usage: sudo ./scripts/prod/deploy.sh <40-hex-sha>'
  local new_tag=$1
  valid_tag "$new_tag" || fail 'Invalid tag: provide exactly 40 lowercase hexadecimal characters.'

  local lock_file=${DEPLOY_LOCK_FILE:-/run/lock/ecommerce-deploy.lock}
  [[ ! -L $lock_file && ( ! -e $lock_file || -f $lock_file ) ]] \
    || fail 'Lock must be a regular file, not a symbolic link.'
  exec {lock_fd}>> "$lock_file"
  flock -n "$lock_fd" || fail 'Another deployment is in progress.'

  root_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
  env_file="$root_dir/.env"
  check_env || fail '.env must exist and be a regular file, not a symbolic link.'
  cd -- "$root_dir"
  docker_bin=${DOCKER_BIN:-docker}
  health_timeout=${DEPLOY_HEALTH_TIMEOUT:-300}
  health_interval=${DEPLOY_HEALTH_INTERVAL:-5}
  [[ $health_timeout =~ ^(0|[1-9][0-9]{0,5})$ ]] \
    || fail 'DEPLOY_HEALTH_TIMEOUT must be an integer between 0 and 999999 seconds.'
  [[ $health_interval =~ ^(0|[1-9][0-9]{0,5})$ ]] \
    || fail 'DEPLOY_HEALTH_INTERVAL must be an integer between 0 and 999999 seconds.'
  read_previous_tag
  if [[ -z $previous_tag ]]; then
    printf '==> First deployment: there will be no automatic rollback.\n' >&2
  elif [[ $new_tag == "$previous_tag" ]]; then
    printf '==> The requested tag is already active; proceeding to repair the apps.\n' >&2
  fi

  trap on_exit EXIT
  trap 'fail "Deployment interrupted."' HUP INT TERM
  printf '==> Pulling images for %s.\n' "$new_tag"
  compose "$new_tag" pull "${APPS[@]}" >/dev/null 2>&1 \
    || fail "Pull failed; .env and containers were not changed. Diagnostics: sudo IMAGE_TAG=$new_tag docker compose pull"
  print_digests "$new_tag" || fail 'Failed to retrieve digests; .env and containers were not changed.'
  write_tag "$new_tag" || fail 'Failed to write and validate the new tag in .env.'
  up_apps "$new_tag" || fail 'Failed to start the apps. Diagnostics: sudo docker compose logs --tail 100 <service>'
  wait_healthy "$new_tag" || fail 'The apps did not become healthy.'
  printf '==> Active tag: %s\n' "$new_tag"
  compose "$new_tag" ps "${APPS[@]}" 2>/dev/null || fail 'Failed to list the apps.'
  completed=1
}

main "$@"
