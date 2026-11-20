#!/usr/bin/env bash
# Fixed current-page layout task. Generic queued Runner owns all host lifecycle.
# Admitted cases: ui-{circle,geometry}-{100,500,1000,2500}-{native,new,polygon}
# plus the historical non-UI geometry-{100,500,1000,2500}-{native,new} set.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$root/scripts/preview/host-validation-env.sh"
[[ $# -ge 3 ]] || { echo "usage: atlas $(turboism_task_versions_usage atlas) run-label {ui-}{circle,geometry}-{100,500,1000,2500}-{native,new,polygon} [--prepare-dir DIR|--dry-run|runner option forwards]" >&2; exit 2; }
version="$1"; label="$2"; variant="$3"; shift 3
turboism_require_task_version atlas "$version" || exit 2
parallel=false
case "$variant" in
  ui-circle-100-new-parallel|ui-geometry-100-new-parallel|ui-circle-500-new-parallel|ui-geometry-500-new-parallel)
    parallel=true; variant="${variant%-parallel}" ;;
esac
dataset=geometry; ui_timing=false
heavy=false
if [[ "$variant" == ui-heavy-0-polygon ]]; then
  heavy=true; dataset=heavy; ui_timing=true
fi
case "$variant" in
  ui-circle-*-native|ui-circle-*-new|ui-circle-*-polygon) dataset=circle; ui_timing=true ;;
  ui-geometry-*-native|ui-geometry-*-new|ui-geometry-*-polygon) ui_timing=true ;;
esac
count=""
if [[ "$heavy" == true ]]; then
  count=0
fi
case "$variant" in
  *-100-native|*-100-new|*-100-polygon) count=100 ;;
  *-500-native|*-500-new|*-500-polygon) count=500 ;;
  *-1000-native|*-1000-new|*-1000-polygon) count=1000 ;;
  *-2500-native|*-2500-new|*-2500-polygon) count=2500 ;;
esac
[[ -n "$count" ]] || { echo 'Unadmitted Atlas case' >&2; exit 2; }
case "$variant" in
  *-native|*-new|*-polygon) implementation="${variant##*-}" ;;
  *) echo 'Unadmitted Atlas case' >&2; exit 2 ;;
esac
fixture_name="atlas_mapping_geometry_$count.cmo3"
[[ "$dataset" != circle ]] || fixture_name="atlas_mapping_$count.cmo3"
[[ "$heavy" != true ]] || fixture_name=heavy.cmo3
hash="$(turboism_fixture_sha256 "$fixture_name")" || exit 2
result_timeout=7500
[[ "$count" != 2500 ]] || result_timeout=21900
# UI matrix uses a 30-minute result ceiling, except geometry-1000-native whose
# measured host method time alone was ~1635 s; allow 45 minutes for that case.
[[ "$ui_timing" != true ]] || result_timeout=1800
layout_deadline=1800
[[ "$heavy" != true ]] || { layout_deadline=120; result_timeout=600; }
if [[ "$ui_timing" == true && "$variant" == ui-geometry-1000-native ]]; then
  result_timeout=2700; layout_deadline=2700
fi
# No fixture/Agent/hook overrides through this capability wrapper; the
# startup-assistance runner flags (--aux-agent/--jvm-option/--linux-env/
# --windows-env) forward verbatim so -assisted variants can stage the audited
# prompt responder.
extra=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prepare-dir|--aux-agent|--jvm-option|--linux-env|--windows-env)
      [[ $# -ge 2 ]] || { echo 'Unsupported Atlas wrapper options' >&2; exit 2; }
      extra+=("$1" "$2"); shift 2 ;;
    --dry-run) extra+=(--dry-run); shift ;;
    *) echo 'Unsupported Atlas wrapper options' >&2; exit 2 ;;
  esac
done
id="$(bash "$root/scripts/dev/worktree-id.sh")"
bundle="$root/build/preview/$id"
agent="$bundle/turboism-agent.jar"
probe="$root/build/atlas-queue-probe/atlas-queue-probe.jar"
shopt -s nullglob
maxrects=("$root/build/worktree/$id/atlas-maxrects-bssf/libs/"*.jar)
dalsoo=("$root/build/worktree/$id/atlas-dalsoo-polygon/libs/"*.jar)
[[ ${#maxrects[@]} == 1 && ${#dalsoo[@]} == 1 && -f "$probe" && -f "$agent" ]] || { echo 'Build previewBundle, both Atlas plugin jars and package-atlas-queue-probe.sh first' >&2; exit 2; }
fixture="$root/testing/assets/texture-atlas-layout/cmo3/$dataset/$fixture_name"
if [[ "$heavy" == true ]]; then
  fixture="$root/testing/assets/texture-atlas-layout/heavy.cmo3"
fi
exec bash "$root/scripts/preview/run-cubism-host-validation.sh" \
  --name atlas --version "$version" --run-label "$label-$variant-parallel-$parallel" \
  --bundle-root "$bundle" --agent "$agent" \
  --plugin "${maxrects[0]}:atlas-maxrects-bssf.jar" \
  --plugin "${dalsoo[0]}:atlas-dalsoo-polygon.jar" \
  --aux-agent "$probe:atlas-queue-probe.jar" \
  --fixture-local "$fixture" --fixture-sha256 "$hash" --require-fixture-unchanged \
  --fixture-name "$fixture_name" \
  --jvm-option "-Dturboism.validation.atlas.count=$count" \
  --jvm-option "-Dturboism.validation.atlas.implementation=$implementation" \
  --jvm-option "-Dturboism.validation.atlas.dataset=$dataset" \
  --jvm-option "-Dturboism.validation.atlas.uiTiming=$ui_timing" \
  --jvm-option "-Dturboism.validation.atlas.parallel=$parallel" \
  --jvm-option "-Dturboism.validation.atlas.layoutDeadlineSeconds=$layout_deadline" \
  --jvm-option '-Dturboism.validation.atlas.fixtureName={FIXTURE_NAME}' \
  --result-file state/atlas-validation/result.txt \
  --result-timeout "$result_timeout" --exit-timeout 120 \
  "${extra[@]}"
