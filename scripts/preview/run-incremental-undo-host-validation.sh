#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec bash "$repo/validation/incremental-update-host-probe/run-undo-host-validation.sh" "$@"
