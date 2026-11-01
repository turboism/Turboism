#!/usr/bin/env bash
# Negative self-test for scripts/dev/paseo-worktree-teardown.sh.
#
# Proves the teardown hook no longer reports success when host-artifact cleanup
# fails or cannot be proven:
#   1. cleanup script exits non-zero -> teardown exits non-zero
#   2. cleanup script missing       -> teardown exits non-zero
#   3. cleanup script succeeds      -> teardown exits zero and cleans both slugs
#   4. invalid slug                 -> teardown exits non-zero and the engine never sees it
#
# Fixtures live in a temporary directory; no real workspace or host artifact is touched.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WORK_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/turboism-teardown-selftest.XXXXXX")"
TOTAL=0
FAILED=0

fail() {
  echo "FAIL: $*" >&2
  FAILED=$((FAILED + 1))
}

pass() {
  echo "ok: $*"
}

cleanup() {
  rm -rf "${WORK_ROOT}"
}
trap cleanup EXIT

# $1 = case name; creates <case>/worktree and <case>/checkout, prints the case root.
make_fixture() {
  local name="$1"
  local root="${WORK_ROOT}/${name}"
  mkdir -p "${root}/worktree/scripts/dev" "${root}/checkout/scripts/dev"
  printf '%s\n' "${root}"
}

run_teardown() {
  local root="$1"
  local branch="$2"
  PASEO_WORKTREE_PATH="${root}/worktree" \
    PASEO_SOURCE_CHECKOUT_PATH="${root}/checkout" \
    PASEO_BRANCH_NAME="${branch}" \
    bash "${SCRIPT_DIR}/paseo-worktree-teardown.sh"
}

# ---------- 1. failing cleanup must fail the teardown ----------
TOTAL=$((TOTAL + 1))
root="$(make_fixture failing)"
cat >"${root}/worktree/scripts/dev/cleanup-host-artifacts.sh" <<'STUB'
#!/usr/bin/env bash
echo "simulated host artifact cleanup failure" >&2
exit 3
STUB
chmod +x "${root}/worktree/scripts/dev/cleanup-host-artifacts.sh"
if run_teardown "${root}" "fix/example-lane" >"${WORK_ROOT}/failing.log" 2>&1; then
  fail "failing cleanup: expected non-zero exit"
elif ! grep -q "Teardown FAILED" "${WORK_ROOT}/failing.log"; then
  fail "failing cleanup: failure not reported"
elif grep -q "Teardown completed successfully" "${WORK_ROOT}/failing.log"; then
  fail "failing cleanup: success still reported"
else
  pass "cleanup failure propagates as a non-zero teardown"
fi

# ---------- 2. missing cleanup engine must fail the teardown ----------
TOTAL=$((TOTAL + 1))
root="$(make_fixture missing)"
if run_teardown "${root}" "fix/example-lane" >"${WORK_ROOT}/missing.log" 2>&1; then
  fail "missing cleanup engine: expected non-zero exit"
elif ! grep -q "cannot be proven" "${WORK_ROOT}/missing.log"; then
  fail "missing cleanup engine: reason not reported"
else
  pass "missing cleanup engine fails closed"
fi

# ---------- 3. successful cleanup exits zero and covers both slugs ----------
TOTAL=$((TOTAL + 1))
root="$(make_fixture success)"
cat >"${root}/worktree/scripts/dev/cleanup-host-artifacts.sh" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"${root}/invocations.log"
exit 0
STUB
chmod +x "${root}/worktree/scripts/dev/cleanup-host-artifacts.sh"
if ! run_teardown "${root}" "fix/example-lane" >"${WORK_ROOT}/success.log" 2>&1; then
  fail "successful cleanup: expected zero exit, output: $(cat "${WORK_ROOT}/success.log")"
elif ! grep -q -- "--worktree worktree" "${root}/invocations.log"; then
  fail "successful cleanup: worktree directory slug not cleaned"
elif ! grep -q -- "--worktree fix-example-lane" "${root}/invocations.log"; then
  fail "successful cleanup: branch slug not cleaned"
elif ! grep -q "Teardown completed successfully" "${WORK_ROOT}/success.log"; then
  fail "successful cleanup: success not reported"
else
  pass "successful cleanup cleans both slugs and exits zero"
fi

# ---------- 4. an invalid slug never reaches the cleanup engine ----------
TOTAL=$((TOTAL + 1))
root="${WORK_ROOT}/invalid"
mkdir -p "${root}/Bad_Name/scripts/dev" "${root}/checkout/scripts/dev"
cat >"${root}/Bad_Name/scripts/dev/cleanup-host-artifacts.sh" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >>"${root}/invocations.log"
exit 0
STUB
chmod +x "${root}/Bad_Name/scripts/dev/cleanup-host-artifacts.sh"
if PASEO_WORKTREE_PATH="${root}/Bad_Name" \
  PASEO_SOURCE_CHECKOUT_PATH="${root}/checkout" \
  PASEO_BRANCH_NAME="refs/heads/x" \
  bash "${SCRIPT_DIR}/paseo-worktree-teardown.sh" >"${WORK_ROOT}/invalid.log" 2>&1; then
  fail "invalid slug: expected non-zero exit"
elif [ -s "${root}/invocations.log" ]; then
  fail "invalid slug: the engine was invoked with $(cat "${root}/invocations.log")"
elif ! grep -q "not a valid worktree id" "${WORK_ROOT}/invalid.log"; then
  fail "invalid slug: refusal reason not reported"
elif ! grep -q "Teardown FAILED" "${WORK_ROOT}/invalid.log"; then
  fail "invalid slug: failure not reported"
else
  pass "invalid slugs fail the teardown and never reach the cleanup engine"
fi

echo "paseo teardown self-test: $((TOTAL - FAILED))/${TOTAL} cases passed"
[ "${FAILED}" -eq 0 ]
