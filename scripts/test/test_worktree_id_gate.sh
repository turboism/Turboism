#!/usr/bin/env bash
set -euo pipefail

# Regression coverage for the worktree-ID fail-closed gate:
#  - an unrelated devCheck Exec task must ignore a forbidden TURBOISM_WORKTREE_ID;
#  - a host-validation package task must reject it before invoking its script;
#  - Gradle reports the same id/verdict as scripts/dev/worktree-id.sh --resolve;
#  - when the probe cannot run, Gradle falls back to sanitized naming and still
#    fails closed on consumers.
# All Gradle calls use --no-daemon so the test environment actually reaches the
# build instead of a reused daemon's environment.

unset TURBOISM_NIGHTLY_VERSION TURBOISM_BUILD_NUMBER TURBOISM_SOURCE_REVISION TURBOISM_BUILD_VERSION TURBOISM_BUILD_CHANNEL ORG_GRADLE_PROJECT_turboismRelease

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd -- "${SCRIPT_DIR}/../.." && pwd -P)"
TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/turboism-worktree-id-gate.XXXXXX")"
trap 'rm -rf -- "$TEMP_ROOT"' EXIT

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

cd "${REPO_ROOT}"

# Test 1: an unrelated devCheck Exec gate task succeeds under a forbidden id.
TURBOISM_WORKTREE_ID=test ./gradlew --no-daemon --max-workers=2 -q checkPackageLayout \
  > "${TEMP_ROOT}/unrelated.log" 2>&1 \
  || { cat "${TEMP_ROOT}/unrelated.log"; fail "unrelated Exec task must not consume the worktree id"; }

# Test 2: a host-validation package task fails closed before running its script.
if TURBOISM_WORKTREE_ID=test ./gradlew --no-daemon --max-workers=2 packageThemeHostValidation \
    -x previewBundle -x :plugins:ui-theme:jar \
    > "${TEMP_ROOT}/host.log" 2>&1; then
  cat "${TEMP_ROOT}/host.log"
  fail "host-validation task must reject a forbidden worktree id"
fi
grep -F "Forbidden worktree ID: test" "${TEMP_ROOT}/host.log" >/dev/null \
  || { cat "${TEMP_ROOT}/host.log"; fail "host-validation rejection must carry the script verdict"; }
if grep -F "package-windows-theme-validation.sh" "${TEMP_ROOT}/host.log" >/dev/null; then
  cat "${TEMP_ROOT}/host.log"
  fail "packaging script must not run after fail-closed rejection"
fi

# Test 3: Gradle resolution reports the script verdict verbatim (single source).
print_info() {
  TURBOISM_WORKTREE_ID="$1" ./gradlew --no-daemon --max-workers=2 -q printBuildInfo
}
for sample in "m15-wrapper-test" "test" "1invalid" "My Weird_ID!"; do
  expected_id=$(TURBOISM_WORKTREE_ID="$sample" bash scripts/dev/worktree-id.sh --resolve 2>/dev/null)
  expected_err=$(TURBOISM_WORKTREE_ID="$sample" bash scripts/dev/worktree-id.sh --resolve 2>&1 >/dev/null)
  info=$(print_info "$sample") || fail "printBuildInfo failed for sample: $sample"
  got_id=$(grep '^worktreeId=' <<<"$info" | cut -d= -f2-)
  got_err=$(grep '^worktreeIdError=' <<<"$info" | cut -d= -f2-)
  [ "$got_id" = "$expected_id" ] \
    || fail "id drift for '$sample': gradle='$got_id' script='$expected_id'"
  [ "$got_err" = "$expected_err" ] \
    || fail "verdict drift for '$sample': gradle='$got_err' script='$expected_err'"
done

# Test 4: probe-unavailable fallback keeps sanitized naming and fails closed.
mkdir -p "${TEMP_ROOT}/fakebin"
cat > "${TEMP_ROOT}/fakebin/bash" <<'FAKE'
#!/bin/sh
exit 127
FAKE
chmod +x "${TEMP_ROOT}/fakebin/bash"
for sample in "my-ok-id" "My Weird_ID!"; do
  expected_id=$(TURBOISM_WORKTREE_ID="$sample" bash scripts/dev/worktree-id.sh --resolve 2>/dev/null)
  info=$(PATH="${TEMP_ROOT}/fakebin:$PATH" TURBOISM_WORKTREE_ID="$sample" \
    ./gradlew --no-daemon --max-workers=2 -q printBuildInfo) \
    || fail "printBuildInfo failed without bash for sample: $sample"
  got_id=$(grep '^worktreeId=' <<<"$info" | cut -d= -f2-)
  got_err=$(grep '^worktreeIdError=' <<<"$info" | cut -d= -f2-)
  [ "$got_id" = "$expected_id" ] \
    || fail "fallback id drift for '$sample': gradle='$got_id' script='$expected_id'"
  [ -n "$got_err" ] \
    || fail "consumers must fail closed when the probe cannot run (sample: $sample)"
done

echo "PASS: worktree id consumer gate"
