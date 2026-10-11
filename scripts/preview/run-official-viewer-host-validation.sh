#!/usr/bin/env bash
set -euo pipefail
exec python3 -B "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host_validation_official_viewer.py" prepare "$@"
