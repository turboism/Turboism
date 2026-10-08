#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

if [ "$#" -lt 1 ]; then
  echo "Usage: $0 <worktree-id> [base-ref]" >&2
  exit 1
fi

WORKTREE_ID="$1"
BASE_REF="${2:-HEAD}"

# Validate ID against the shared worktree-id rules (same implementation the
# resolver uses; never a copied regex that can drift).
# shellcheck source=worktree-id-lib.sh
. "${SCRIPT_DIR}/worktree-id-lib.sh"
validate_id "${WORKTREE_ID}" || exit 1

WORKTREE_PARENT="${REPO_ROOT}/../turboism-worktrees"
WORKTREE_DIR="${WORKTREE_PARENT}/${WORKTREE_ID}"
BRANCH="worktree/${WORKTREE_ID}"

mkdir -p "${WORKTREE_PARENT}"

if git rev-parse --verify "${BRANCH}" >/dev/null 2>&1; then
  echo "Branch ${BRANCH} already exists." >&2
else
  git branch "${BRANCH}" "${BASE_REF}"
fi

if [ -d "${WORKTREE_DIR}" ]; then
  echo "Worktree ${WORKTREE_DIR} already exists." >&2
else
  git worktree add "${WORKTREE_DIR}" "${BRANCH}"
fi

echo "${WORKTREE_ID}" > "${WORKTREE_DIR}/.turboism-worktree-id"

echo "Worktree created: ${WORKTREE_DIR}"
echo "Next steps:"
echo "  cd ${WORKTREE_DIR}"
echo "  ./scripts/dev/build-worktree.sh"
echo "  ./scripts/dev/test-worktree.sh"
