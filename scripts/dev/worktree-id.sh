#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
# shellcheck source=worktree-id-lib.sh
. "${SCRIPT_DIR}/worktree-id-lib.sh"

resolve_worktree_id() {
  local wt_root="${REPO_ROOT}"
  local candidate=""

  if [ -n "${TURBOISM_WORKTREE_ID:-}" ]; then
    candidate="$TURBOISM_WORKTREE_ID"
  elif [ -f "$wt_root/.turboism-worktree-id" ]; then
    candidate="$(sed -n '1{s/[[:space:]]//g;p;q;}' "$wt_root/.turboism-worktree-id")"
  elif git -C "$wt_root" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    candidate="$(git -C "$wt_root" branch --show-current 2>/dev/null || true)"
    case "$candidate" in
      main|master) candidate="main" ;;
      worktree/*) candidate="${candidate#worktree/}" ;;
      '') candidate="$(basename "$wt_root")" ;;
    esac
  else
    candidate="$(basename "$wt_root")"
  fi

  candidate="$(sanitize_turboism_worktree_id "$candidate")"
  [ -n "$candidate" ] || candidate="worktree"
  printf '%s\n' "$candidate"
}

resolve_only=0
if [ "${1:-}" = "--resolve" ]; then
  resolve_only=1
fi

id=$(resolve_worktree_id)
if [ "${resolve_only}" -eq 0 ]; then
  validate_id "${id}" || exit 1
else
  # Resolve mode reports the verdict on stderr without failing so build
  # configuration can proceed; consumers fail closed on that verdict.
  validate_id "${id}" || true
fi
echo "${id}"
