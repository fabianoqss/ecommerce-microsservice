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
  [[ $# -eq 1 ]] || fail 'Usage: sudo ./scripts/prod/release.sh <40-hex-sha>'
  local sha=$1
  valid_sha "$sha" || fail 'Invalid SHA: provide exactly 40 lowercase hexadecimal characters.'
  [[ $EUID -eq 0 ]] || fail 'Run with sudo.'

  root_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd) \
    || fail 'Failed to locate the repository.'
  git_bin=${GIT_BIN:-git}
  local deploy_script=${DEPLOY_SCRIPT:-"$root_dir/scripts/prod/deploy.sh"}
  local changes object_type

  run_git fetch --quiet origin refs/heads/main:refs/remotes/origin/main >/dev/null 2>&1 \
    || fail 'Failed to fetch main from origin; checkout and deployment were not performed.'
  run_git cat-file -e "$sha^{commit}" >/dev/null 2>&1 \
    || fail 'Commit does not exist or SHA does not identify a commit.'
  object_type=$(run_git cat-file -t "$sha" 2>/dev/null) \
    || fail 'Failed to check the Git object type.'
  [[ $object_type == commit ]] || fail 'SHA does not identify a commit.'
  run_git merge-base --is-ancestor "$sha" refs/remotes/origin/main >/dev/null 2>&1 \
    || fail 'commit is not on main; release refused.'

  changes=$(run_git status --porcelain --untracked-files=no 2>/dev/null) \
    || fail 'Failed to check for local changes.'
  [[ -z $changes ]] || fail 'Local changes in tracked files; release refused.'

  run_git checkout --quiet -B main "$sha" >/dev/null 2>&1 \
    || fail 'Failed to check out the requested commit.'
  printf '==> Repository at %s\n' "$sha"
  exec "$deploy_script" "$sha"
}

main "$@"
