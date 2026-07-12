#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
LEDGER="${REPO_ROOT}/docs/migration/automated-tranche-ledger.tsv"

python3 "${SCRIPT_DIR}/validate_automated_tranche_ledger.py" \
  --target-phase phase6 --repo-root "${REPO_ROOT}" "${LEDGER}"
python3 "${SCRIPT_DIR}/validate_automated_tranche_closure.py" --repo-root "${REPO_ROOT}"
python3 "${SCRIPT_DIR}/test_automated_tranche_closure_regression.py"

echo "PASS: automated tranche closure gate"
