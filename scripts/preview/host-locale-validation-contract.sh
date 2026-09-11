#!/usr/bin/env bash
# Shell contract checks for the host-locale validation wrapper chain.
# Build/test-only: never launches Cubism and never produces readiness evidence.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
LAUNCH="$ROOT/scripts/preview/launch-cubism-host-locale-validation.sh"
ADAPTER="$ROOT/scripts/preview/run-host-locale-host-validation.sh"
HOOK="$ROOT/scripts/preview/host-locale-environment-language-hook.sh"

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
#    reaches the shared runner. The runner's own refusal depends on the local
#    build/env state (missing agent bundle, missing golden prefix, unresolvable
#    host fixture), so the contract pins only the part that must never happen
#    here: an identity rejection before the runner is engaged.
run "adapter-accepts-with-hash" 1 "" "host validation:" -- \
  bash "$ADAPTER" 5302 --fixture 'C:\fixture\model.cmo3' --fixture-sha256 "$HASH"

# 8. Pinned default fixtures keep their pinned hashes (no override, no hash needed).
run "adapter-help" 0 "usage:" "" -- bash "$ADAPTER" --help

# 9. Environment Settings language seeding: the adapter validates the host
#    language set, and the pre-launch hook fails closed without a supported value.
run "adapter-rejects-unknown-environment-language" 2 "" "environment language must be en, ja, ko, or zh" -- \
  bash "$ADAPTER" 5302 --environment-language fr --fixture 'C:\fixture\model.cmo3' --fixture-sha256 "$HASH"
run "adapter-rejects-missing-environment-language-value" 2 "" "missing value for --environment-language" -- \
  bash "$ADAPTER" 5302 --environment-language
run "adapter-accepts-environment-language" 1 "" "host validation:" -- \
  bash "$ADAPTER" 5302 --locale system --environment-language ja \
  --fixture 'C:\fixture\model.cmo3' --fixture-sha256 "$HASH"
# The runner's context argument 7 is the lane version token (5203/5302); the
# full Cubism version strings stay accepted for standalone use. Both forms are
# pinned here because a mismatch fails the real run before launch.
run "hook-requires-runner-arguments" 1 "" "missing runner hook arguments" -- \
  bash "$HOOK"
run "hook-rejects-unknown-language" 1 "" "unsupported language" -- \
  bash "$HOOK" task home /tmp/evidence /tmp/host-locale-contract-missing-prefix fixture runid 5302 300 wrapper runner :0 fr
run "hook-rejects-unknown-version" 1 "" "unsupported Cubism version" -- \
  bash "$HOOK" task home /tmp/evidence /tmp/host-locale-contract-missing-prefix fixture runid 5399 300 wrapper runner :0 ja
# The language must be readable from the trailing argument (the queue drops the
# ambient environment), and an absent language must fail closed.
run "hook-reads-language-from-argument" 1 "" "has no users directory" -- \
  bash "$HOOK" task home /tmp/evidence /tmp/host-locale-contract-missing-prefix fixture runid 5302 300 wrapper runner :0 en
run "hook-accepts-full-version-form" 1 "" "has no users directory" -- \
  bash "$HOOK" task home /tmp/evidence /tmp/host-locale-contract-missing-prefix fixture runid 5.3.02 300 wrapper runner :0 en
run "hook-argument-overrides-environment" 1 "" "unsupported language" -- \
  env TURBOISM_HOST_VALIDATION_ENVIRONMENT_LANGUAGE=en \
  bash "$HOOK" task home /tmp/evidence /tmp/host-locale-contract-missing-prefix fixture runid 5302 300 wrapper runner :0 fr
run "hook-rejects-missing-language" 1 "" "missing environment language argument" -- \
  bash "$HOOK" task home /tmp/evidence /tmp/host-locale-contract-missing-prefix fixture runid 5302 300 wrapper runner :0
run "hook-accepts-environment-fallback" 1 "" "has no users directory" -- \
  env TURBOISM_HOST_VALIDATION_ENVIRONMENT_LANGUAGE=ja \
  bash "$HOOK" task home /tmp/evidence /tmp/host-locale-contract-missing-prefix fixture runid 5203 300 wrapper runner :0
  bash "$HOOK" task home /tmp/evidence /tmp/host-locale-contract-missing-prefix fixture runid 5.3.02 300 wrapper runner :0

printf 'host-locale contract: %s checks passed\n' "$checks"
