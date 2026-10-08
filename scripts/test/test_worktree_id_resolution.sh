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
cp "${REPO_ROOT}/scripts/dev/worktree-id.sh" "${REPO_ROOT}/scripts/dev/worktree-id-lib.sh" "${WORKDIR}/scripts/dev/"
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
cp "${REPO_ROOT}/scripts/dev/worktree-id.sh" "${REPO_ROOT}/scripts/dev/worktree-id-lib.sh" "${WORKDIR}/dir-name-id/scripts/dev/"
id=$(cd "${WORKDIR}/dir-name-id" && env -u TURBOISM_WORKTREE_ID bash scripts/dev/worktree-id.sh)
[ "${id}" = "dir-name-id" ] || fail "directory name fallback failed"
rm -rf "${WORKDIR}"

# Test 6: --resolve prints a forbidden id instead of failing
id=$(cd "${REPO_ROOT}" && TURBOISM_WORKTREE_ID=test bash scripts/dev/worktree-id.sh --resolve)
[ "${id}" = "test" ] || fail "--resolve should still print the forbidden id"

# Test 7: --resolve prints an invalid id instead of failing
id=$(cd "${REPO_ROOT}" && TURBOISM_WORKTREE_ID=1invalid bash scripts/dev/worktree-id.sh --resolve)
[ "${id}" = "1invalid" ] || fail "--resolve should still print the invalid id"

# Test 8: --resolve still sanitizes the candidate
id=$(cd "${REPO_ROOT}" && TURBOISM_WORKTREE_ID='My Weird_ID!' bash scripts/dev/worktree-id.sh --resolve)
[ "${id}" = "my-weird-id" ] || fail "--resolve should sanitize the id"

# Test 9: --resolve reports the forbidden verdict on stderr without failing
err=$(cd "${REPO_ROOT}" && TURBOISM_WORKTREE_ID=test bash scripts/dev/worktree-id.sh --resolve 2>&1 >/dev/null)
[ "${err}" = "Forbidden worktree ID: test" ] || fail "--resolve should report the forbidden verdict on stderr, got: ${err}"

# Test 10: --resolve reports the invalid verdict on stderr without failing
err=$(cd "${REPO_ROOT}" && TURBOISM_WORKTREE_ID=1invalid bash scripts/dev/worktree-id.sh --resolve 2>&1 >/dev/null)
[ "${err}" = "Invalid worktree ID: 1invalid (must match [a-z][a-z0-9-]{2,63})" ] \
  || fail "--resolve should report the invalid verdict on stderr, got: ${err}"

# Test 11: --resolve reports an empty verdict for a valid id
err=$(cd "${REPO_ROOT}" && TURBOISM_WORKTREE_ID=valid-id bash scripts/dev/worktree-id.sh --resolve 2>&1 >/dev/null)
[ -z "${err}" ] || fail "--resolve should report an empty verdict for a valid id, got: ${err}"

echo "PASS: worktree id resolution"
