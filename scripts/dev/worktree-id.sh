#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

sanitize_turboism_worktree_id() {
  local raw="$1"
  local sanitized
  sanitized="$(printf '%s' "$raw" \
    | tr '[:upper:]' '[:lower:]' \
    | sed -E 's/[^a-z0-9.-]+/-/g; s/^-+//; s/-+$//; s/-{2,}/-/g')"
  printf '%s\n' "$sanitized"
}

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

validate_id() {
  local id="$1"
  if [[ ! "${id}" =~ ^[a-z][a-z0-9-]{2,63}$ ]]; then
    echo "Invalid worktree ID: ${id} (must match [a-z][a-z0-9-]{2,63})" >&2
    exit 1
  fi
  case "${id}" in
    test|tmp|new|main-copy|my-work)
      echo "Forbidden worktree ID: ${id}" >&2
      exit 1
      ;;
  esac
}

id=$(resolve_worktree_id)
validate_id "${id}"
echo "${id}"
