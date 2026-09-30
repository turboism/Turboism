#!/usr/bin/env bash
# Wrapper script for Proton exact-host validation artifact and temporary file cleanup.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"

# Ensure execution of the Python engine
exec python3 "${SCRIPT_DIR}/cleanup_host_artifacts.py" "$@"
