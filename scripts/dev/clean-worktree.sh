#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <worktree-id>" >&2
  exit 1
fi

WORKTREE_ID="$1"
WORKTREE_DIR="${REPO_ROOT}/../turboism-worktrees/${WORKTREE_ID}"
BRANCH="worktree/${WORKTREE_ID}"

if [ -d "${WORKTREE_DIR}" ]; then
  git worktree remove "${WORKTREE_DIR}" || true
  rm -rf "${WORKTREE_DIR}"
fi

if git rev-parse --verify "${BRANCH}" >/dev/null 2>&1; then
  git branch -D "${BRANCH}"
fi

echo "Worktree ${WORKTREE_ID} cleaned."
