#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
python3 scripts/test/test_host_validation_scheduler.py
python3 scripts/test/test_host_validation_queue.py
python3 scripts/test/test_host_validation_evidence.py
bash scripts/test/test_host_validation_env.sh
bash scripts/test/test_cubism_host_validation_project_copy.sh
bash scripts/test/test_fps_resize_driver.sh
bash scripts/test/test_cubism_host_validation_local.sh
bash scripts/test/test_cubism_host_validation_cleanup.sh
bash scripts/test/test_backup_interactive_session_local.sh
bash scripts/test/test_status_bar_host_probe_packaging.sh
