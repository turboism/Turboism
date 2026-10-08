#!/usr/bin/env bash
# Shared worktree-id rules, sourced by worktree-id.sh and create-worktree.sh so the
# sanitize and validate steps can never drift: the sanitize charset is the same
# [a-z0-9-] alphabet validate accepts, so a dot in a raw branch name becomes '-'
# instead of surviving to a late validate failure.

sanitize_turboism_worktree_id() {
  local raw="$1"
  local sanitized
  sanitized="$(printf '%s' "$raw" \
    | tr '[:upper:]' '[:lower:]' \
    | sed -E 's/[^a-z0-9-]+/-/g; s/^-+//; s/-+$//; s/-{2,}/-/g')"
  printf '%s\n' "$sanitized"
}

validate_id() {
  local id="$1"
  if [[ ! "${id}" =~ ^[a-z][a-z0-9-]{2,63}$ ]]; then
    echo "Invalid worktree ID: ${id} (must match [a-z][a-z0-9-]{2,63})" >&2
    return 1
  fi
  case "${id}" in
    test|tmp|new|main-copy|my-work)
      echo "Forbidden worktree ID: ${id}" >&2
      return 1
      ;;
  esac
  return 0
}
