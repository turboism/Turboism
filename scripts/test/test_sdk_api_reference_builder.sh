#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
BUILDER="$ROOT/scripts/test/build_sdk_api_reference.py"
ANCHOR="d47cc7be36174cae7a28a0ddf68507451ce1e3a6"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

fail() {
  printf 'SDK API reference builder selftest: %s\n' "$*" >&2
  exit 1
}

python3 "$BUILDER" --root "$ROOT" --commit "$ANCHOR" --output "$TMP/a.jar"
python3 "$BUILDER" --root "$ROOT" --commit "$ANCHOR" --output "$TMP/b.jar"
cmp -s "$TMP/a.jar" "$TMP/b.jar" || fail 'same immutable anchor produced different JAR bytes'

if python3 "$BUILDER" \
  --root "$ROOT" \
  --commit 0000000000000000000000000000000000000000 \
  --output "$TMP/missing.jar" >/dev/null 2>&1; then
  fail 'missing anchor unexpectedly produced a reference JAR'
fi

if python3 "$BUILDER" \
  --root "$ROOT" \
  --commit invalid \
  --output "$TMP/invalid.jar" >/dev/null 2>&1; then
  fail 'malformed anchor unexpectedly produced a reference JAR'
fi

printf '%s\n' 'SDK API reference builder selftest passed.'
