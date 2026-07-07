#!/usr/bin/env bash
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKTREE_PARENT="${REPO_ROOT}/../turboism-worktrees"

if [ -d "${WORKTREE_PARENT}" ]; then
  find "${WORKTREE_PARENT}" -maxdepth 1 -mindepth 1 -type d -printf '%f\n'
else
  echo "No worktree directory found."
fi
