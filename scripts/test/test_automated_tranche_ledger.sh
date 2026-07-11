#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
python3 "${SCRIPT_DIR}/validate_automated_tranche_ledger.py" \
  --repo-root "${REPO_ROOT}" \
  "${REPO_ROOT}/docs/migration/automated-tranche-ledger.tsv"
