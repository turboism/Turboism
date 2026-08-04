#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd -P)"
RUNNER="${REPO_ROOT}/scripts/preview/run-cubism-host-validation.sh"
TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/turboism-host-validation-lock.XXXXXX")"
FIRST_PID=''

cleanup() {
  if [ -n "$FIRST_PID" ]; then
    kill "$FIRST_PID" 2>/dev/null || true
    wait "$FIRST_PID" 2>/dev/null || true
  fi
  rm -rf -- "$TEMP_ROOT"
}
trap cleanup EXIT

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

BUNDLE_ROOT="${TEMP_ROOT}/bundle"
FAKE_BIN="${TEMP_ROOT}/bin"
LOCK_FILE="${TEMP_ROOT}/runner.lock"
SSH_RELEASE="${TEMP_ROOT}/release"
SSH_KEY="${TEMP_ROOT}/ssh-key"
mkdir -p "$BUNDLE_ROOT" "$FAKE_BIN"
printf 'agent\n' > "${BUNDLE_ROOT}/agent.jar"
printf 'plugin\n' > "${BUNDLE_ROOT}/plugin.jar"
printf 'fixture\n' > "${BUNDLE_ROOT}/fixture.cmo3"
: > "$SSH_KEY"

cat > "${FAKE_BIN}/ssh" <<'SSH'
#!/usr/bin/env bash
set -euo pipefail
printf 'ssh %s\n' "$*" >> "${SSH_LOG:?}"
: > "${SSH_MARKER:?}"
if [ "${SSH_BLOCK:-0}" = 1 ]; then
  while [ ! -e "${SSH_RELEASE:?}" ]; do
    sleep 0.01
done
fi
exit 1
SSH
cat > "${FAKE_BIN}/scp" <<'SCP'
#!/usr/bin/env bash
set -euo pipefail
printf 'scp %s\n' "$*" >> "${SSH_LOG:?}"
exit 1
SCP
chmod +x "${FAKE_BIN}/ssh" "${FAKE_BIN}/scp"

RUN_ARGS=(
  bash "$RUNNER"
  --name lock-regression
  --version 5302
  --bundle-root "$BUNDLE_ROOT"
  --agent "$BUNDLE_ROOT/agent.jar"
  --plugin "$BUNDLE_ROOT/plugin.jar"
  --fixture-local "$BUNDLE_ROOT/fixture.cmo3"
  --result-marker never
  --ssh-key "$SSH_KEY"
)

FIRST_MARKER="${TEMP_ROOT}/first-ssh"
FIRST_LOG="${TEMP_ROOT}/first.log"
FIRST_OUTPUT="${TEMP_ROOT}/first.out"
PATH="${FAKE_BIN}:$PATH" \
  SSH_MARKER="$FIRST_MARKER" SSH_RELEASE="$SSH_RELEASE" SSH_LOG="$FIRST_LOG" SSH_BLOCK=1 \
  TURBOISM_HOST_VALIDATION_LOCK_FILE="$LOCK_FILE" \
  "${RUN_ARGS[@]}" >"$FIRST_OUTPUT" 2>&1 &
FIRST_PID=$!

for _ in $(seq 1 200); do
  [ -e "$FIRST_MARKER" ] && break
  kill -0 "$FIRST_PID" 2>/dev/null || break
  sleep 0.01
done
[ -e "$FIRST_MARKER" ] || { cat "$FIRST_OUTPUT" >&2; fail "first runner did not reach fake SSH"; }
[ -f "$LOCK_FILE" ] || fail "runner did not create the override lock file"

SECOND_MARKER="${TEMP_ROOT}/second-ssh"
SECOND_LOG="${TEMP_ROOT}/second.log"
SECOND_OUTPUT="${TEMP_ROOT}/second.out"
set +e
PATH="${FAKE_BIN}:$PATH" \
  SSH_MARKER="$SECOND_MARKER" SSH_RELEASE="$SSH_RELEASE" SSH_LOG="$SECOND_LOG" SSH_BLOCK=0 \
  TURBOISM_HOST_VALIDATION_LOCK_FILE="$LOCK_FILE" \
  "${RUN_ARGS[@]}" >"$SECOND_OUTPUT" 2>&1
SECOND_STATUS=$?
set -e
[ "$SECOND_STATUS" -ne 0 ] || fail "second runner unexpectedly succeeded"
grep -Fq 'host validation lock is busy' "$SECOND_OUTPUT" \
  || { cat "$SECOND_OUTPUT" >&2; fail "second runner did not report a busy lock"; }
[ ! -e "$SECOND_MARKER" ] || fail "busy runner reached SSH"

: > "$SSH_RELEASE"
set +e
wait "$FIRST_PID"
FIRST_STATUS=$?
set -e
FIRST_PID=''
[ "$FIRST_STATUS" -ne 0 ] || fail "fake first runner unexpectedly succeeded"

THIRD_MARKER="${TEMP_ROOT}/third-ssh"
THIRD_LOG="${TEMP_ROOT}/third.log"
THIRD_OUTPUT="${TEMP_ROOT}/third.out"
set +e
PATH="${FAKE_BIN}:$PATH" \
  SSH_MARKER="$THIRD_MARKER" SSH_RELEASE="$SSH_RELEASE" SSH_LOG="$THIRD_LOG" SSH_BLOCK=0 \
  TURBOISM_HOST_VALIDATION_LOCK_FILE="$LOCK_FILE" \
  "${RUN_ARGS[@]}" >"$THIRD_OUTPUT" 2>&1
THIRD_STATUS=$?
set -e
[ "$THIRD_STATUS" -ne 0 ] || fail "third runner unexpectedly succeeded"
[ -e "$THIRD_MARKER" ] || fail "released lock was not reacquired"
if grep -Fq 'host validation lock is busy' "$THIRD_OUTPUT"; then
  cat "$THIRD_OUTPUT" >&2
  fail "released runner still reported a busy lock"
fi

expect_local_lock_failure() {
  local label="$1" lock_value="$2" marker="$3" output="$4" status
  set +e
  PATH="${FAKE_BIN}:$PATH" \
    SSH_MARKER="$marker" SSH_RELEASE="$SSH_RELEASE" SSH_LOG="${TEMP_ROOT}/${label}.log" SSH_BLOCK=0 \
    TURBOISM_HOST_VALIDATION_LOCK_FILE="$lock_value" \
    "${RUN_ARGS[@]}" >"$output" 2>&1
  status=$?
  set -e
  [ "$status" -ne 0 ] || fail "$label lock override unexpectedly succeeded"
  grep -Fq 'host validation:' "$output" \
    || { cat "$output" >&2; fail "$label override did not fail with a host validation message"; }
  [ ! -e "$marker" ] || fail "$label override reached SSH"
}

expect_local_lock_failure \
  invalid "${TEMP_ROOT}/missing/runner.lock" "${TEMP_ROOT}/invalid-ssh" "${TEMP_ROOT}/invalid.out"
expect_local_lock_failure \
  empty '' "${TEMP_ROOT}/empty-ssh" "${TEMP_ROOT}/empty.out"

DRY_MARKER="${TEMP_ROOT}/dry-ssh"
DRY_LOG="${TEMP_ROOT}/dry.log"
DRY_OUTPUT="${TEMP_ROOT}/dry.out"
if ! exec {dry_lock_fd}>>"$LOCK_FILE"; then
  fail "test could not open lock file"
fi
flock -n "$dry_lock_fd" || fail "test could not hold lock file"
if ! PATH="${FAKE_BIN}:$PATH" \
  SSH_MARKER="$DRY_MARKER" SSH_RELEASE="$SSH_RELEASE" SSH_LOG="$DRY_LOG" SSH_BLOCK=0 \
  TURBOISM_HOST_VALIDATION_LOCK_FILE="$LOCK_FILE" \
  "${RUN_ARGS[@]}" --dry-run >"$DRY_OUTPUT" 2>&1; then
  cat "$DRY_OUTPUT" >&2
  fail "dry-run failed while lock was held"
fi
[ ! -e "$DRY_MARKER" ] || fail "dry-run reached SSH"
[ ! -e "$DRY_LOG" ] || fail "dry-run invoked the SSH stub"
exec {dry_lock_fd}>&-

echo "PASS: Cubism host validation lock serializes, releases, rejects bad overrides, and skips dry-run"
