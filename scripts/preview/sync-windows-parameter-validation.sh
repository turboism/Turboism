#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

sync_env_file="${TURBOISM_SYNC_ENV_FILE:-.turboism-sync.env}"
explicit_drop="${TURBOISM_WINDOWS_DROP_ROOT+x}"
explicit_worktrees_root="${TURBOISM_WINDOWS_WORKTREES_ROOT+x}"
explicit_sync_method="${TURBOISM_SYNC_METHOD+x}"
pre_drop="${TURBOISM_WINDOWS_DROP_ROOT:-}"
pre_worktrees_root="${TURBOISM_WINDOWS_WORKTREES_ROOT:-}"
pre_sync_method="${TURBOISM_SYNC_METHOD:-}"
if [ -f "$sync_env_file" ]; then
  set -a
  # shellcheck disable=SC1090
  . "$sync_env_file"
  set +a
fi
[ -n "$explicit_drop" ] && TURBOISM_WINDOWS_DROP_ROOT="$pre_drop"
[ -n "$explicit_worktrees_root" ] && TURBOISM_WINDOWS_WORKTREES_ROOT="$pre_worktrees_root"
[ -n "$explicit_sync_method" ] && TURBOISM_SYNC_METHOD="$pre_sync_method"

worktree_id="$(bash scripts/dev/worktree-id.sh)"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-parameter-validation"
drop_root="${TURBOISM_WINDOWS_DROP_ROOT:-}"
worktrees_root="${TURBOISM_WINDOWS_WORKTREES_ROOT:-}"
sync_method="${TURBOISM_SYNC_METHOD:-scp}"
sync_timeout="${TURBOISM_SYNC_TIMEOUT_SECONDS:-60}"

fail() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

[ -n "$drop_root" ] || [ -n "$worktrees_root" ] || fail \
  "set TURBOISM_WINDOWS_DROP_ROOT or TURBOISM_WINDOWS_WORKTREES_ROOT"
case "$sync_timeout" in
  ''|*[!0-9]*) fail "TURBOISM_SYNC_TIMEOUT_SECONDS must be a non-negative integer" ;;
esac
[ -d "$bundle_root" ] || fail \
  "validation bundle not found: $bundle_root; run the packaging task first"

files=(
  README.md
  SHA256SUMS.txt
  launch-cubism-parameter-validation.ps1
  run-parameter-validation.bat
  turboism-agent.jar
  plugins/parameter.jar
  plugins/parameter-validation-probe.jar
  plugins/editor-object-peer-validation-probe.jar
)
for relative in "${files[@]}"; do
  [ -f "$bundle_root/$relative" ] || fail "bundle file not found: $relative"
done

join_path() {
  local root="$1"
  shift
  local separator="/"
  case "$root" in
    *:*) separator="\\" ;;
  esac
  for part in "$@"; do
    root="${root%/}"
    root="${root%\\}"
    root="$root$separator$part"
  done
  printf '%s\n' "$root"
}

if [ -n "$worktrees_root" ]; then
  destination="$(join_path "$worktrees_root" "$worktree_id" manual windows-parameter-validation)"
else
  destination="$(join_path "$drop_root" worktrees "$worktree_id" manual windows-parameter-validation)"
fi

printf '[sync] worktree: %s\n' "$worktree_id"
printf '[sync] bundle:   windows-parameter-validation\n'
printf '[sync] target:   configured Windows worktree drop\n'

case "$destination" in
  *:*)
    [ "$sync_method" = scp ] || fail "remote validation sync currently requires TURBOISM_SYNC_METHOD=scp"
    host="${destination%%:*}"
    remote_root="${destination#*:}"
    ssh_opts=(-o ConnectTimeout=10 -o ServerAliveInterval=5 -o ServerAliveCountMax=2)
    if [ -n "${TURBOISM_SSH_KEY:-}" ]; then
      ssh_opts+=(-i "$TURBOISM_SSH_KEY" -o IdentitiesOnly=yes)
    fi
    if [ -n "${TURBOISM_SSH_OPTS:-}" ]; then
      # shellcheck disable=SC2206
      extra_ssh_opts=(${TURBOISM_SSH_OPTS})
      ssh_opts+=("${extra_ssh_opts[@]}")
    fi
    scp_opts=()
    if [ -n "${TURBOISM_SCP_OPTS:-}" ]; then
      # shellcheck disable=SC2206
      extra_scp_opts=(${TURBOISM_SCP_OPTS})
      scp_opts+=("${extra_scp_opts[@]}")
    fi

    run_remote() {
      if [ "$sync_timeout" = 0 ] || ! command -v timeout >/dev/null 2>&1; then
        "$@"
      else
        timeout "${sync_timeout}s" "$@"
      fi
    }

    remote_plugins="$(join_path "$remote_root" plugins)"
    run_remote ssh "${ssh_opts[@]}" "$host" \
      "powershell -NoProfile -Command \"New-Item -ItemType Directory -Path '$remote_root','$remote_plugins' -Force | Out-Null\""

    for relative in "${files[@]}"; do
      remote_directory="$remote_root"
      [ "$(dirname "$relative")" = . ] || remote_directory="$(join_path "$remote_root" "$(dirname "$relative")")"
      run_remote scp "${scp_opts[@]}" "${ssh_opts[@]}" \
        "$bundle_root/$relative" "$host:$(join_path "$remote_directory" "$(basename "$relative")")"
    done

    for relative in "${files[@]}"; do
      expected="$(sha256sum "$bundle_root/$relative" | cut -d' ' -f1)"
      remote_file="$(join_path "$remote_root" "$relative")"
      actual="$(run_remote ssh "${ssh_opts[@]}" "$host" \
        "powershell -NoProfile -Command \"(Get-FileHash -Algorithm SHA256 -Path '$remote_file').Hash.ToLowerInvariant()\"" \
        | tr -d '\r[:space:]')"
      [ "$actual" = "$expected" ] || fail "Windows checksum mismatch: $relative"
    done
    ;;
  *)
    [ "$sync_method" = cp ] || fail "local validation sync requires TURBOISM_SYNC_METHOD=cp"
    mkdir -p "$destination/plugins"
    for relative in "${files[@]}"; do
      mkdir -p "$destination/$(dirname "$relative")"
      cp -f "$bundle_root/$relative" "$destination/$relative"
      cmp -s "$bundle_root/$relative" "$destination/$relative" || fail \
        "local checksum mismatch: $relative"
    done
    ;;
esac

printf '[sync] verified %s files\n' "${#files[@]}"
printf '[sync] SHA256SUMS.txt: %s\n' \
  "$(sha256sum "$bundle_root/SHA256SUMS.txt" | cut -d' ' -f1)"
printf '[sync] done\n'
