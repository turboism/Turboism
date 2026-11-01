#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
exec bash "$repo/validation/product-ui-lifecycle-host-probe/run-host-validation.sh" "$@"
