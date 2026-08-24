#!/usr/bin/env bash
set -euo pipefail

ROOT="$(git rev-parse --show-toplevel)"
TOOL="$ROOT/scripts/test/sdk_api_baseline_cli.py"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

python3 "$ROOT/scripts/test/sdk_api_baseline_selftest.py" --tool "$TOOL" --tmp "$TMP"

printf '%s\n' 'SDK API baseline selftest passed.'
