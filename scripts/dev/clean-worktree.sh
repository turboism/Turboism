#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <worktree-id> [--dry-run]" >&2
  exit 1
fi

WORKTREE_ID="$1"
DRY_RUN=0
if [ "${2:-}" = "--dry-run" ]; then
  DRY_RUN=1
fi

WORKTREE_DIR="${REPO_ROOT}/../turboism-worktrees/${WORKTREE_ID}"
BRANCH="worktree/${WORKTREE_ID}"

if [ "$DRY_RUN" -eq 1 ]; then
  echo "[DRY-RUN] Would remove worktree: ${WORKTREE_DIR}"
  echo "[DRY-RUN] Would delete branch: ${BRANCH}"
  if [ -x "${SCRIPT_DIR}/cleanup-host-artifacts.sh" ]; then
    "${SCRIPT_DIR}/cleanup-host-artifacts.sh" --worktree "${WORKTREE_ID}" --dry-run
  fi
  exit 0
fi

if [ -d "${WORKTREE_DIR}" ]; then
  git worktree remove "${WORKTREE_DIR}" || true
  rm -rf "${WORKTREE_DIR}"
fi

if git rev-parse --verify "${BRANCH}" >/dev/null 2>&1; then
  git branch -D "${BRANCH}"
fi

# Automatically clean host validation artifacts and build bundles for this worktree
if [ "${TURBOISM_SKIP_HOST_CLEANUP:-0}" != "1" ] && [ -x "${SCRIPT_DIR}/cleanup-host-artifacts.sh" ]; then
  echo "Cleaning host validation artifacts for worktree ${WORKTREE_ID}..."
  "${SCRIPT_DIR}/cleanup-host-artifacts.sh" --worktree "${WORKTREE_ID}" || echo "Warning: host cleanup encountered non-fatal issues." >&2
fi

echo "Worktree ${WORKTREE_ID} cleaned."
