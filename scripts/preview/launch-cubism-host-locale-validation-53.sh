#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec bash "$script_dir/launch-cubism-host-locale-validation.sh" --version 5.3.02 "$@"
