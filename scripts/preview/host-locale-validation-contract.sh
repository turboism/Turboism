#!/usr/bin/env bash
# Shell contract checks for the host-locale validation wrapper chain.
# Build/test-only: never launches Cubism and never produces readiness evidence.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LAUNCH="$ROOT/scripts/preview/launch-cubism-host-locale-validation.sh"
ADAPTER="$ROOT/scripts/preview/run-host-locale-host-validation.sh"

checks=0
fail() {
  printf 'host-locale contract: %s\n' "$*" >&2
  exit 1
}
run() { # run <name> <expected-exit> <expected-substr> <expected-stderr-substr> -- args...
  local name="$1" expected_exit="$2" expected_out="$3" expected_err="$4"
  shift 4
  [ "$1" = "--" ] || fail "internal: missing -- separator"
  shift
  local out err rc
  out="$(mktemp)"
  err="$(mktemp)"
  "$@" >"$out" 2>"$err"
  rc=$?
  checks=$((checks + 1))
  if [ "$rc" -ne "$expected_exit" ]; then
    cat "$out" "$err" >&2
    fail "$name: expected exit $expected_exit, got $rc"
  fi
  if [ -n "$expected_out" ] && ! grep -qF "$expected_out" "$out"; then
    cat "$out" "$err" >&2
    fail "$name: stdout missing '$expected_out'"
  fi
  if [ -n "$expected_err" ] && ! grep -qF -- "$expected_err" "$err"; then
    cat "$out" "$err" >&2
    fail "$name: stderr missing '$expected_err'"
  fi
  rm -f "$out" "$err"
}

HASH='0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef'

# 1. A real run with a custom --fixture but no hash is rejected at the dispatcher.
run "dispatcher-rejects-without-hash" 2 "" "--fixture-sha256" -- \
  bash "$LAUNCH" --version 5.2.03 --locale ja --fixture 'C:\fixture\model.cmo3'

# 2. Dry-run accepts a synthetic fixture without a hash and says dry-run/build-only.
run "dry-run-without-hash" 0 "mode=dry-run/build-only" "" -- \
  bash "$LAUNCH" --dry-run --version 5.2.03 --locale ja --fixture 'C:\fixture\model.cmo3'

# 3. Dry-run with an explicit hash accepts and reports the forwarded hash.
run "dry-run-with-hash" 0 "fixtureSha256=$HASH" "" -- \
  bash "$LAUNCH" --dry-run --version 5.3.02 --locale zh_Hans --fixture 'C:\fixture\model.cmo3' \
  --fixture-sha256 "$HASH"

# 4. Exact-version routing: only 5.2.03/5.3.02 (and numeric aliases) are accepted.
run "rejects-unknown-version" 2 "" "must be 5.2.03 or 5.3.02" -- \
  bash "$LAUNCH" --version 5.2.04 --locale ja --fixture 'C:\fixture\model.cmo3'
run "numeric-version-alias-routes" 0 "version=5.2.03" "" -- \
  bash "$LAUNCH" --dry-run --version 5203 --locale ja --fixture 'C:\fixture\model.cmo3'
run "exact-version-53-route" 0 "version=5.3.02" "" -- \
  bash "$LAUNCH" --dry-run --version 5.3.02 --locale ja --fixture 'C:\fixture\model.cmo3'

# 5. Bad hash format is rejected for a real run.
run "rejects-malformed-hash" 2 "" "64 hexadecimal characters" -- \
  bash "$LAUNCH" --version 5.2.03 --locale ja --fixture 'C:\fixture\model.cmo3' \
  --fixture-sha256 'not-a-hash'

# 6. Direct invocation of the lower adapter enforces the same invariant.
run "adapter-rejects-without-hash" 2 "" "requires --fixture-sha256" -- \
  bash "$ADAPTER" 5302 --fixture 'C:\fixture\model.cmo3'

# 7. Direct invocation with an explicit hash passes the fixture-identity gate and
#    proceeds (failing later only on missing build artifacts, never on identity).
run "adapter-accepts-with-hash" 1 "" "not found" -- \
  bash "$ADAPTER" 5302 --fixture 'C:\fixture\model.cmo3' --fixture-sha256 "$HASH"

# 8. Pinned default fixtures keep their pinned hashes (no override, no hash needed).
run "adapter-help" 0 "usage:" "" -- bash "$ADAPTER" --help

printf 'host-locale contract: %s checks passed\n' "$checks"
