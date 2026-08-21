#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
python3 scripts/test/test_host_validation_scheduler.py
bash scripts/test/test_cubism_host_validation_project_copy.sh
bash scripts/test/test_fps_resize_driver.sh
