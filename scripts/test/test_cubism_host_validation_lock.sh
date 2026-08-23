#!/usr/bin/env bash
# Compatibility entrypoint retained for callers of the former global-lock test.
# Host validation now permits concurrent task-scoped project copies.
set -euo pipefail
script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
exec bash "$script_dir/test_cubism_host_validation_project_copy.sh"
