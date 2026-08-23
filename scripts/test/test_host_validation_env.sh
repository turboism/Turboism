#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
loader="$root/scripts/preview/host-validation-env.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
fail() { echo "host validation env test: $*" >&2; exit 1; }

cat > "$tmp/.env" <<'ENV'
# Local data only; values with spaces may be quoted.
TURBOISM_HOST_VALIDATION_SSH_HOST=env@example.invalid
TURBOISM_HOST_VALIDATION_FIXTURE_5302='/remote/fixture with spaces.cmo3'
TURBOISM_HOST_VALIDATION_REMOTE_ROOT=/remote/tasks
ENV

result="$(
  TURBOISM_ENV_FILE="$tmp/.env" \
  TURBOISM_HOST_VALIDATION_SSH_HOST=exported@example.invalid \
  bash -c 'source "$1"; printf "%s\n%s\n%s\n" \
    "$TURBOISM_HOST_VALIDATION_SSH_HOST" \
    "$TURBOISM_HOST_VALIDATION_FIXTURE_5302" \
    "$TURBOISM_HOST_VALIDATION_REMOTE_ROOT"' _ "$loader"
)"
mapfile -t values <<< "$result"
[ "${values[0]}" = exported@example.invalid ] || fail "exported value did not override .env"
[ "${values[1]}" = '/remote/fixture with spaces.cmo3' ] || fail "quoted path was not parsed as data"
[ "${values[2]}" = /remote/tasks ] || fail "unquoted path was not parsed"

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

echo "host validation env test: PASS"
