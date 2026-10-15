#!/usr/bin/env bash
# Thin capability wrapper; generic Runner owns identity, isolation and the queue.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec bash "$repo_root/scripts/preview/run-cubism-host-validation.sh" \
  --name model-viewer \
  --ready-marker 'Turboism Developer Preview started' \
  --result-file state/dev.turboism.validation.model-viewer/result.properties \
  --require-fixture-unchanged \
  "$@"
