#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

# Test 1: env variable wins
id=$(cd "${REPO_ROOT}" && TURBOISM_WORKTREE_ID=env-id bash scripts/dev/worktree-id.sh)
[ "${id}" = "env-id" ] || fail "env variable should win"

# Test 2: .turboism-worktree-id file
WORKDIR="$(mktemp -d)"
mkdir -p "${WORKDIR}/scripts/dev"
cp "${REPO_ROOT}/scripts/dev/worktree-id.sh" "${WORKDIR}/scripts/dev/"
echo "file-id" > "${WORKDIR}/.turboism-worktree-id"
id=$(cd "${WORKDIR}" && env -u TURBOISM_WORKTREE_ID bash scripts/dev/worktree-id.sh)
[ "${id}" = "file-id" ] || fail "file should win when env is absent"
rm -rf "${WORKDIR}"

# Test 3: invalid id must fail
if (cd "${REPO_ROOT}" && TURBOISM_WORKTREE_ID=1invalid bash scripts/dev/worktree-id.sh) 2>/dev/null; then
  fail "invalid id should fail"
fi

# Test 4: forbidden id must fail
if (cd "${REPO_ROOT}" && TURBOISM_WORKTREE_ID=test bash scripts/dev/worktree-id.sh) 2>/dev/null; then
  fail "forbidden id should fail"
fi

# Test 5: directory name fallback
WORKDIR="$(mktemp -d)"
mkdir -p "${WORKDIR}/scripts/dev"
cp "${REPO_ROOT}/scripts/dev/worktree-id.sh" "${WORKDIR}/scripts/dev/"
mkdir -p "${WORKDIR}/dir-name-id/scripts/dev"
cp "${REPO_ROOT}/scripts/dev/worktree-id.sh" "${WORKDIR}/dir-name-id/scripts/dev/"
id=$(cd "${WORKDIR}/dir-name-id" && env -u TURBOISM_WORKTREE_ID bash scripts/dev/worktree-id.sh)
[ "${id}" = "dir-name-id" ] || fail "directory name fallback failed"
rm -rf "${WORKDIR}"

echo "PASS: worktree id resolution"
