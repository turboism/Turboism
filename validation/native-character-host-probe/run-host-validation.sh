#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec bash "$repo_root/scripts/preview/run-cubism-host-validation.sh" \
  --name native-character \
  --ready-marker 'Turboism Developer Preview started' \
  --result-file state/dev.turboism.validation.native-character/result.properties \
  --require-fixture-unchanged \
  "$@"
