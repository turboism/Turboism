#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)
CATALOG="$ROOT_DIR/docs/migration/capabilities/capability-catalog.tsv"
MATRIX="$ROOT_DIR/docs/migration/capabilities/plugin-readiness-matrix.tsv"
BOARD="$ROOT_DIR/docs/migration/migration-board.tsv"

if [[ ! -f "$CATALOG" || ! -f "$MATRIX" || ! -f "$BOARD" ]]; then
  echo "FAIL: M12 capability catalog, plugin readiness matrix, or migration board is missing" >&2
  exit 1
fi

capabilities=$(mktemp)
statuses=$(mktemp)
trap 'rm -f "$capabilities" "$statuses"' EXIT
cut -f1 "$CATALOG" | tail -n +2 | sort > "$capabilities"
awk -F'\t' 'NR > 1 { print $1 "\t" $14 }' "$CATALOG" > "$statuses"

surface_exists() {
  local surface="$1"
  [[ "$surface" == internal\ semantic\ ingress\ * ]] && return 0
  local rel="${surface//.//}.java"
  [[ -f "$ROOT_DIR/sdk/src/main/java/$rel" || -f "$ROOT_DIR/runtime/src/main/java/$rel" ]]
}

status_rank() {
  case "$1" in
    planned) echo 0 ;;
    draft) echo 1 ;;
    fake-verified) echo 2 ;;
    adapter-ready) echo 3 ;;
    production-ready) echo 4 ;;
    deferred) echo -1 ;;
    *) echo -2 ;;
  esac
}

required_rank() {
  case "$1" in
    shell-ready|blocked) echo 0 ;;
    fake-ready) echo 2 ;;
    adapter-ready) echo 3 ;;
    production-ready) echo 4 ;;
    *) echo 99 ;;
  esac
}

missing=0
production_ready=0
blocked_high_risk=0
while IFS=$'\t' read -r plugin legacy_rows required readiness production_blocked_by next_slice; do
  [[ "$plugin" == "plugin" || -z "$plugin" ]] && continue
  IFS=';' read -ra required_ids <<< "$required"
  for capability_id in "${required_ids[@]}"; do
    if ! grep -qx "$capability_id" "$capabilities"; then
      echo "FAIL: $plugin references missing capability $capability_id" >&2
      missing=1
      continue
    fi
    capability_status=$(awk -F'\t' -v id="$capability_id" '$1 == id { print $2 }' "$statuses")
    if [[ $(status_rank "$capability_status") -lt $(required_rank "$readiness") ]]; then
      echo "FAIL: $plugin is $readiness but $capability_id is only $capability_status" >&2
      missing=1
    fi
  done
  if [[ "$readiness" == "production-ready" ]]; then
    production_ready=$((production_ready + 1))
  fi
  if [[ "$plugin" =~ ^turboism\.(parameter|mesh-edit|psd-import)$ && "$readiness" == "blocked" && -n "$production_blocked_by" ]]; then
    blocked_high_risk=$((blocked_high_risk + 1))
  fi
  if [[ "$readiness" != "production-ready" && -z "$production_blocked_by" ]]; then
    echo "FAIL: $plugin is $readiness but has no production blocker" >&2
    missing=1
  fi
done < "$MATRIX"

while IFS=$'\t' read -r capability_id category sdk_surface runtime_owner adapter_owner permissions requires_transaction requires_hook requires_mapping threading_budget fake_host diagnostics legacy_rows status; do
  [[ "$capability_id" == "capabilityId" || -z "$capability_id" ]] && continue
  if [[ $(status_rank "$status") -ge $(status_rank draft) ]] && ! surface_exists "$sdk_surface"; then
    echo "FAIL: $capability_id is $status but surface does not exist: $sdk_surface" >&2
    missing=1
  fi
  IFS=';' read -ra row_ids <<< "$legacy_rows"
  for row_id in "${row_ids[@]}"; do
    evidence=$(awk -F'\t' -v id="$row_id" '$1 == id { print $5 "\t" $7 }' "$BOARD")
    if [[ -z "$evidence" ]]; then
      echo "FAIL: $capability_id references unknown migration row $row_id" >&2
      missing=1
      continue
    fi
    IFS=$'\t' read -r reuse_level board_status <<< "$evidence"
    if [[ ( "$reuse_level" == "L0" || "$board_status" == "NEVER" ) && "$status" != "deferred" ]]; then
      echo "FAIL: $capability_id must defer L0/NEVER evidence $row_id" >&2
      missing=1
    fi
    if [[ ( "$reuse_level" == "L1" || "$board_status" == "QUARANTINE" ) && $(status_rank "$status") -gt $(status_rank planned) ]]; then
      echo "FAIL: $capability_id must not promote L1/QUARANTINE evidence above planned: $row_id" >&2
      missing=1
    fi
  done
done < "$CATALOG"

if [[ "$production_ready" -ne 0 ]]; then
  echo "FAIL: M12 readiness gate must not mark any plugin production-ready" >&2
  exit 1
fi
if [[ "$blocked_high_risk" -ne 3 ]]; then
  echo "FAIL: M12 readiness gate must keep parameter, mesh-edit, and psd-import blocked" >&2
  exit 1
fi
if [[ "$missing" -ne 0 ]]; then
  exit 1
fi

echo "PASS: M12 plugin readiness gate"
