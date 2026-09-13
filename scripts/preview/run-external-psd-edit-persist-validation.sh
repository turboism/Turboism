#!/usr/bin/env bash
# Two-stage 025 persistence evidence: stage A runs the pipeline with the mediated
# SAVE_AS persist tail; stage B reopens the saved copy and verifies the
# external-edit layer marker survived the real native save.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
wrapper="$root/scripts/preview/run-external-psd-edit-host-validation.sh"
host_root="${TURBOISM_HOST_VALIDATION_ROOT:-$HOME/TurboismValidation}"
label="025-us4"

echo '== stage A: pipeline + SAVE_AS persist tail'
EXTERNAL_PSD_PHASE=pipeline EXTERNAL_PSD_PERSIST=1 bash "$wrapper" "$@"

task_dir="$(ls -dt "$host_root"/external-psd-edit-pipeline/5302-"$label"*/queue-*/ | head -1)"
result_a="$task_dir/evidence/result/external-psd-edit-result.properties"
[ -f "$result_a" ] || { echo "stage A result missing: $result_a" >&2; exit 1; }
grep -q '^status=PASS' "$result_a" || { echo 'stage A did not PASS' >&2; exit 1; }

saved="$task_dir/turboism-home/persisted-document.cmo3"
[ -f "$saved" ] || { echo "persisted document missing: $saved" >&2; exit 1; }

prop() { grep -E "^$1=" "$result_a" | head -1 | cut -d= -f2-; }
layer="$(prop persist.markerLayer)"
offset="$(prop persist.markerOffset)"
char="$(prop persist.markerChar)"
[ -n "$layer" ] && [ -n "$offset" ] && [ -n "$char" ] \
  || { echo 'stage A did not record mutation marker coordinates' >&2; exit 1; }

echo "== stage B: reopen $saved (marker layer=$layer offset=$offset char=$char)"
EXTERNAL_PSD_PHASE=reopen \
  EXTERNAL_PSD_FIXTURE_LOCAL="$saved" \
  EXTERNAL_PSD_MARKERLAYER="$layer" \
  EXTERNAL_PSD_MARKEROFFSET="$offset" \
  EXTERNAL_PSD_MARKERCHAR="$char" \
  bash "$wrapper" "$@"
