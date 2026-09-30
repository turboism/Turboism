#!/usr/bin/env bash
# Teardown hook executed by Paseo when deleting a worktree workspace.
# Runs inside the worktree directory before directory deletion.
set -euo pipefail

WORKTREE_DIR="${PASEO_WORKTREE_PATH:-${PWD}}"
REPO_ROOT="${PASEO_SOURCE_CHECKOUT_PATH:-/opt/dev/projects/turboism}"
CLEANUP_SCRIPT="${REPO_ROOT}/scripts/dev/cleanup-host-artifacts.sh"

echo "[paseo-worktree-teardown] Teardown triggered for worktree: ${WORKTREE_DIR}"

# 1. Never clear CodeGraph locks from teardown. A lock may belong to a live
# indexer or daemon; proving it stale requires a separate ownership inspection.
# Workspace deletion, not an unconditional unlock, owns disposable index files.
if [ -d "${WORKTREE_DIR}/.codegraph" ]; then
  echo "[paseo-worktree-teardown] Leaving CodeGraph lock ownership unchanged."
fi

# 2. Derive worktree identifier(s) for external host artifact cleanup
WT_DIRNAME="$(basename "${WORKTREE_DIR}")"
BRANCH="${PASEO_BRANCH_NAME:-}"
BRANCH_SLUG="${BRANCH#refs/heads/}"
BRANCH_SLUG="${BRANCH_SLUG#worktree/}"

# 3. Clean external host validation artifacts associated with this worktree
if [ -x "${CLEANUP_SCRIPT}" ]; then
  if [ -n "${WT_DIRNAME}" ]; then
    echo "[paseo-worktree-teardown] Cleaning host artifacts for worktree slug: ${WT_DIRNAME}"
    "${CLEANUP_SCRIPT}" --worktree "${WT_DIRNAME}" || echo "[paseo-worktree-teardown] Warning: host cleanup for ${WT_DIRNAME} encountered non-fatal issues." >&2
  fi

  if [ -n "${BRANCH_SLUG}" ] && [ "${BRANCH_SLUG}" != "${WT_DIRNAME}" ]; then
    echo "[paseo-worktree-teardown] Cleaning host artifacts for branch slug: ${BRANCH_SLUG}"
    "${CLEANUP_SCRIPT}" --worktree "${BRANCH_SLUG}" || echo "[paseo-worktree-teardown] Warning: host cleanup for ${BRANCH_SLUG} encountered non-fatal issues." >&2
  fi
fi

echo "[paseo-worktree-teardown] Teardown completed successfully."
exit 0
