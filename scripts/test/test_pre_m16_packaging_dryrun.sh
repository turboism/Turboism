#!/usr/bin/env bash
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

PYTHONDONTWRITEBYTECODE=1 python3 "${SCRIPT_DIR}/test_pre_m16_packaging_dryrun.py"

# Keep the wrapper aligned with the exact seven-case matrix executed and attested by H1.
PYTHONDONTWRITEBYTECODE=1 python3 - "${REPO_ROOT}" <<'PY'
import importlib.util
import subprocess
import sys
from pathlib import Path

repo = Path(sys.argv[1])
path = repo / "scripts/release/pre_m16_packaging_dryrun.py"
spec = importlib.util.spec_from_file_location("pre_m16_packaging_dryrun", path)
module = importlib.util.module_from_spec(spec)
assert spec.loader
sys.modules[spec.name] = module
spec.loader.exec_module(module)
for command in module.safe_mode_commands(repo):
    subprocess.run(command, cwd=repo, check=True)
PY

echo "PASS: pre-M16 packaging dry-run H1 gate"
