#!/usr/bin/env bash
# Teardown hook executed by Paseo when deleting a worktree workspace.
# Runs inside the worktree directory before directory deletion.
#
# Fail-closed contract: every cleanup step reports its real result. A failed or
# unprovable cleanup exits non-zero so the workspace deletion is not reported as
# a clean teardown. Warnings never replace a failed exit status here.
set -uo pipefail

WORKTREE_DIR="${PASEO_WORKTREE_PATH:-${PWD}}"
REPO_ROOT="${PASEO_SOURCE_CHECKOUT_PATH:-/opt/dev/projects/turboism}"

failures=0

fail() {
  echo "[paseo-worktree-teardown] error: $*" >&2
  failures=$((failures + 1))
}

echo "[paseo-worktree-teardown] Teardown triggered for worktree: ${WORKTREE_DIR}"

# 1. Never clear CodeGraph locks from teardown. A lock may belong to a live
# indexer or daemon; proving it stale requires a separate ownership inspection.
# Workspace deletion, not an unconditional unlock, owns disposable index files.
if [ -d "${WORKTREE_DIR}/.codegraph" ]; then
  echo "[paseo-worktree-teardown] Leaving CodeGraph lock ownership unchanged."
fi

# 2. Resolve the host-artifact cleanup engine. Prefer the copy shipped in this
# worktree; fall back to the source checkout for layouts that do not carry it.
CLEANUP_SCRIPT=""
for candidate in \
  "${WORKTREE_DIR}/scripts/dev/cleanup-host-artifacts.sh" \
  "${REPO_ROOT}/scripts/dev/cleanup-host-artifacts.sh"; do
  if [ -x "${candidate}" ]; then
    CLEANUP_SCRIPT="${candidate}"
    break
  fi
done

if [ -z "${CLEANUP_SCRIPT}" ]; then
  fail "no cleanup-host-artifacts.sh in ${WORKTREE_DIR} or ${REPO_ROOT}; host artifact cleanup cannot be proven."
fi

# 3. Derive worktree identifier(s) for external host artifact cleanup
WT_DIRNAME="$(basename "${WORKTREE_DIR}")"
BRANCH="${PASEO_BRANCH_NAME:-}"
BRANCH_SLUG="${BRANCH#refs/heads/}"
BRANCH_SLUG="${BRANCH_SLUG#worktree/}"

clean_worktree_artifacts() {
  local slug="$1"
  echo "[paseo-worktree-teardown] Cleaning host artifacts for worktree slug: ${slug}"
  if ! "${CLEANUP_SCRIPT}" --worktree "${slug}"; then
    fail "host artifact cleanup failed for slug ${slug}."
  fi
}

# 4. Clean external host validation artifacts associated with this worktree
if [ -n "${CLEANUP_SCRIPT}" ] && [ -n "${WT_DIRNAME}" ] && [ "${WT_DIRNAME}" != "/" ]; then
  clean_worktree_artifacts "${WT_DIRNAME}"
  if [ -n "${BRANCH_SLUG}" ] && [ "${BRANCH_SLUG}" != "${WT_DIRNAME}" ]; then
    clean_worktree_artifacts "${BRANCH_SLUG}"
  fi
fi

if [ "${failures}" -ne 0 ]; then
  echo "[paseo-worktree-teardown] Teardown FAILED with ${failures} uncleaned step(s)." >&2
  exit 1
fi

echo "[paseo-worktree-teardown] Teardown completed successfully."
exit 0
