#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
MIGRATION_DIR="${REPO_ROOT}/docs/migration"
BOARD_TSV="${MIGRATION_DIR}/migration-board.tsv"
LEGACY_DIR="${MIGRATION_DIR}/legacy-inventory"

fail() {
  echo "FAIL: $1" >&2
  exit 1
}

# 1. Board exists
[ -f "${BOARD_TSV}" ] || fail "migration-board.tsv missing"

# 2. Board header has required columns
header=$(head -n 1 "${BOARD_TSV}")
for col in id phase feature legacyPath reuseLevel target status notes; do
  echo "${header}" | grep -qP "(^|\t)${col}(\t|$)" || fail "board header missing ${col}"
done

# 3. Every row has the same column count as the header
header_cols=$(echo "${header}" | awk -F'\t' '{print NF}')
while IFS= read -r line; do
  [ -z "${line}" ] && continue
  cols=$(echo "${line}" | awk -F'\t' '{print NF}')
  [ "${cols}" -eq "${header_cols}" ] || fail "row column count mismatch: $(echo "${line}" | cut -f1)"
done < <(tail -n +2 "${BOARD_TSV}")

# 4. IDs are non-empty and unique
ids=$(tail -n +2 "${BOARD_TSV}" | cut -f1)
[ -n "$(echo "${ids}" | head -n 1)" ] || fail "board has empty id"
unique_ids=$(echo "${ids}" | sort -u | wc -l)
total_ids=$(echo "${ids}" | wc -l)
[ "${unique_ids}" -eq "${total_ids}" ] || fail "duplicate ids in board: ${unique_ids} unique vs ${total_ids} total"

# 5. reuseLevel is one of L0/L1/L2/L3/L4
while IFS=$'\t' read -r _ _ _ _ level _ _ _ _; do
  case "${level}" in
    L0|L1|L2|L3|L4) ;;
    *) fail "invalid reuseLevel: ${level}" ;;
  esac
done < <(tail -n +2 "${BOARD_TSV}")

# 6. L0 items must have status NEVER
while IFS=$'\t' read -r _ _ _ _ level _ status _ _; do
  if [ "${level}" = "L0" ] && [ "${status}" != "NEVER" ]; then
    fail "L0 item does not have status NEVER: ${level} -> ${status}"
  fi
done < <(tail -n +2 "${BOARD_TSV}")

# 7. Board reuseLevel counts must sum to board row count
board_rows=$(tail -n +2 "${BOARD_TSV}" | wc -l)
l0=$(tail -n +2 "${BOARD_TSV}" | awk -F'\t' '$5=="L0"' | wc -l)
l1=$(tail -n +2 "${BOARD_TSV}" | awk -F'\t' '$5=="L1"' | wc -l)
l2=$(tail -n +2 "${BOARD_TSV}" | awk -F'\t' '$5=="L2"' | wc -l)
l3=$(tail -n +2 "${BOARD_TSV}" | awk -F'\t' '$5=="L3"' | wc -l)
l4=$(tail -n +2 "${BOARD_TSV}" | awk -F'\t' '$5=="L4"' | wc -l)
sum=$((l0 + l1 + l2 + l3 + l4))
[ "${sum}" -eq "${board_rows}" ] || fail "reuseLevel counts ${sum} do not match board rows ${board_rows}"

# 8. Legacy inventory files exist
for f in features.tsv plugins.tsv mapping-inventory.tsv profile-inventory.tsv hook-inventory.tsv \
         runtime-api-inventory.tsv service-inventory.tsv governance-inventory.tsv \
         test-fixture-inventory.tsv asset-inventory.tsv risk-inventory.tsv; do
  [ -f "${LEGACY_DIR}/${f}" ] || fail "legacy-inventory/${f} missing"
done

"${SCRIPT_DIR}/test_m12_plugin_readiness_gate.sh"

"${SCRIPT_DIR}/test_migration_docs_safety_scanner.sh"
"${SCRIPT_DIR}/test_host_ingress_ownership_structure.sh"
"${SCRIPT_DIR}/test_automated_tranche_ledger.sh"
python3 "${SCRIPT_DIR}/test_automated_tranche_ledger_regression.py"
"${SCRIPT_DIR}/test_phase4_build_gates.sh"

echo "PASS: migration inventory sanity (board rows=${board_rows}, L0=${l0}, L1=${l1}, L2=${l2}, L3=${l3}, L4=${l4})"
