#!/usr/bin/env bash
# Negative self-test for scripts/check_shell_lint.sh.
#
# Proves the shell lint gate is fail-closed:
#   1. shellcheck missing        -> non-zero exit with an install hint
#   2. a failing lint run        -> non-zero exit (real findings are not ignored)
#   3. a clean lint run          -> zero exit
#
# The lint runs against this repository's tracked scripts; the shellcheck binary
# is replaced with a stub so no real linter version is required.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LINT_SCRIPT="${REPO_ROOT}/scripts/check_shell_lint.sh"
WORK_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/turboism-shell-lint-selftest.XXXXXX")"
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

run_lint() {
  local shellcheck_bin="$1"
  ( cd "${REPO_ROOT}" && SHELLCHECK_BIN="${shellcheck_bin}" bash "${LINT_SCRIPT}" )
}

# ---------- 1. missing shellcheck fails the gate ----------
TOTAL=$((TOTAL + 1))
if run_lint "${WORK_ROOT}/definitely-absent-shellcheck" >"${WORK_ROOT}/missing.log" 2>&1; then
  fail "missing shellcheck: expected non-zero exit"
elif grep -q "skipping shell lint gate" "${WORK_ROOT}/missing.log"; then
  fail "missing shellcheck: gate still skips"
elif ! grep -q "apt-get install shellcheck" "${WORK_ROOT}/missing.log"; then
  fail "missing shellcheck: install hint not reported"
else
  pass "missing shellcheck fails the gate with an install hint"
fi

# ---------- 2. lint findings still fail the gate ----------
TOTAL=$((TOTAL + 1))
cat >"${WORK_ROOT}/failing-shellcheck" <<'STUB'
#!/usr/bin/env bash
echo "stub shellcheck: simulated error-class finding" >&2
exit 1
STUB
chmod +x "${WORK_ROOT}/failing-shellcheck"
if run_lint "${WORK_ROOT}/failing-shellcheck" >"${WORK_ROOT}/failing.log" 2>&1; then
  fail "failing lint: expected non-zero exit"
elif ! grep -q "simulated error-class finding" "${WORK_ROOT}/failing.log"; then
  fail "failing lint: linter output not propagated"
else
  pass "linter findings fail the gate"
fi

# ---------- 3. a clean run passes ----------
TOTAL=$((TOTAL + 1))
cat >"${WORK_ROOT}/passing-shellcheck" <<STUB
#!/usr/bin/env bash
printf '%s\n' "\$*" >"${WORK_ROOT}/invocation.log"
exit 0
STUB
chmod +x "${WORK_ROOT}/passing-shellcheck"
if ! run_lint "${WORK_ROOT}/passing-shellcheck" >"${WORK_ROOT}/passing.log" 2>&1; then
  fail "clean lint: expected zero exit, output: $(cat "${WORK_ROOT}/passing.log")"
elif ! grep -q -- "-S error --" "${WORK_ROOT}/invocation.log"; then
  fail "clean lint: error-only severity not passed to shellcheck"
elif ! grep -q "OK: shellcheck -S error clean" "${WORK_ROOT}/passing.log"; then
  fail "clean lint: success summary missing"
else
  pass "clean lint run passes"
fi

echo "shell lint self-test: $((TOTAL - FAILED))/${TOTAL} cases passed"
[ "${FAILED}" -eq 0 ]
