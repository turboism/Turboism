#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
loader="$root/scripts/preview/host-validation-env.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail() { echo "host validation env test: $*" >&2; exit 1; }

cat > "$tmp/.env" <<'ENV'
# Local data only; values with spaces may be quoted.
TURBOISM_HOST_VALIDATION_TRANSPORT=local
TURBOISM_HOST_VALIDATION_SSH_HOST=env@example.invalid
TURBOISM_HOST_VALIDATION_FIXTURE_5302='/remote/fixture with spaces.cmo3'
TURBOISM_HOST_VALIDATION_REMOTE_ROOT=/remote/tasks
ENV

result="$(
  TURBOISM_ENV_FILE="$tmp/.env" \
  TURBOISM_HOST_VALIDATION_SSH_HOST=exported@example.invalid \
  bash -c 'source "$1"; printf "%s\n%s\n%s\n%s\n" \
    "$TURBOISM_HOST_VALIDATION_SSH_HOST" \
    "$TURBOISM_HOST_VALIDATION_FIXTURE_5302" \
    "$TURBOISM_HOST_VALIDATION_REMOTE_ROOT" \
    "$TURBOISM_HOST_VALIDATION_TRANSPORT"' _ "$loader"
)"
mapfile -t values <<< "$result"
[ "${values[0]}" = exported@example.invalid ] || fail "exported value did not override .env"
[ "${values[1]}" = '/remote/fixture with spaces.cmo3' ] || fail "quoted path was not parsed as data"
[ "${values[2]}" = /remote/tasks ] || fail "unquoted path was not parsed"
[ "${values[3]}" = local ] || fail "transport value was not parsed"

cat > "$tmp/no-exec.env" <<'ENV'
TURBOISM_HOST_VALIDATION_SSH_HOST=$(:>/tmp/turboism-env-must-not-execute)
ENV
rm -f /tmp/turboism-env-must-not-execute
TURBOISM_ENV_FILE="$tmp/no-exec.env" bash -c 'source "$1"' _ "$loader"
[ ! -e /tmp/turboism-env-must-not-execute ] || fail ".env command substitution was executed"

cat > "$tmp/invalid.env" <<'ENV'
UNSCOPED_SECRET=value
ENV
if TURBOISM_ENV_FILE="$tmp/invalid.env" bash -c 'source "$1"' _ "$loader" >/dev/null 2>&1; then
  fail "unscoped .env key was accepted"
fi

# A wrapper exports the loaded marker then execs the generic runner. Missing
# optional settings must still be initialized when that child sources the loader.
env -i PATH="$PATH" HOME="$HOME" TURBOISM_ENV_FILE="$tmp/missing.env" \
  bash -euc 'source "$1"; bash -euc '\''source "$1"; test -z "$TURBOISM_HOST_VALIDATION_SSH_HOST"; test -n "$TURBOISM_HOST_VALIDATION_FIXTURE_5302_SHA256"'\'' _ "$1"' _ "$loader"

# Manifest-backed task version helpers and fixture selection.
bash -euc '
  source "$1"
  test "$(turboism_dotted_version 5304)" = "5.3.04"
  turboism_require_task_version workspace 5000
  turboism_require_task_version protected-export 5100
  if turboism_require_task_version protected-export 5000 2>/dev/null; then
    exit 1
  fi
  test "$(turboism_expected_core_tuple 5000)" = "5.0.0"
  test "$(turboism_expected_core_tuple 5203)" = "5.0.256"
  test "$(turboism_expected_core_tuple 5302)" = "6.0.257"
  test "$(turboism_expected_core_tuple 5304)" = "6.0.513"
  turboism_core_acquisition_metadata 5304
  test "$profile" = "cubism-5.3.04"
  test "${#editor_jar_sha256}" -eq 64
  test "${#core_jar_sha256}" -eq 64
' _ "$loader" || fail "manifest task version helper check failed"

echo "host validation env test: PASS"
