#!/usr/bin/env bash
# Fixed current-page layout task. Generic queued Runner owns all host lifecycle.
# Admitted cases: ui-{circle,geometry}-{100,500,1000,2500}-{native,new,polygon}
# plus the historical non-UI geometry-{100,500,1000,2500}-{native,new} set.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$root/scripts/preview/host-validation-env.sh"
[[ $# -ge 3 && "$1" =~ ^(5203|5302|5303)$ ]] || { echo 'usage: atlas <5203|5302|5303> run-label {ui-}{circle,geometry}-{100,500,1000,2500}-{native,new,polygon} [--prepare-dir DIR|--dry-run]' >&2; exit 2; }
version="$1"; label="$2"; variant="$3"; shift 3
parallel=false
case "$variant" in
  ui-circle-100-new-parallel|ui-geometry-100-new-parallel|ui-circle-500-new-parallel|ui-geometry-500-new-parallel)
    parallel=true; variant="${variant%-parallel}" ;;
esac
dataset=geometry; ui_timing=false
case "$variant" in
  ui-circle-*-native|ui-circle-*-new|ui-circle-*-polygon) dataset=circle; ui_timing=true ;;
  ui-geometry-*-native|ui-geometry-*-new|ui-geometry-*-polygon) ui_timing=true ;;
esac
circle_100=2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e
circle_500=54ce27647bd8d63d16fc643eb209ef2016fda0d975779b5b07943da303478b50
circle_1000=5a1a4d0e1f27dbffe09eafc5d5f8626776bcad35fab8fcd06c6ecc34b68959f9
circle_2500=3cbd3d8ef91166010c90d5f96d872ef527841b36aadb9e634368cab3d159a239
geometry_100=369c906ad47610a958e770930649f0821f616e8eb66564c39a478c41b49024ff
geometry_500=99038495f8c7fb9ee94cf6b9ce3d60370fc0203c411ed39b0084d84ce72a683b
geometry_1000=2f64a7f5fd4f2abc4cdd0036d1943f8382f30581228ea5f87143b5b367abd3e2
geometry_2500=4bb38d8073cf339b32047bf186514dc7d3709cbfb90dbe665b0a5b0a76daf24b
count=""
case "$variant" in
  *-100-native|*-100-new|*-100-polygon) count=100; hash=$circle_100 ;;
  *-500-native|*-500-new|*-500-polygon) count=500; hash=$circle_500 ;;
  *-1000-native|*-1000-new|*-1000-polygon) count=1000; hash=$circle_1000 ;;
  *-2500-native|*-2500-new|*-2500-polygon) count=2500; hash=$circle_2500 ;;
esac
if [[ "$dataset" == geometry && -n "$count" ]]; then
  case "$count" in
    100) hash=$geometry_100 ;; 500) hash=$geometry_500 ;;
    1000) hash=$geometry_1000 ;; 2500) hash=$geometry_2500 ;;
  esac
fi
[[ -n "$count" ]] || { echo 'Unadmitted Atlas case' >&2; exit 2; }
case "$variant" in
  *-native|*-new|*-polygon) implementation="${variant##*-}" ;;
  *) echo 'Unadmitted Atlas case' >&2; exit 2 ;;
esac
result_timeout=7500
[[ "$count" != 2500 ]] || result_timeout=21900
# UI matrix uses a 30-minute result ceiling, except geometry-1000-native whose
# measured 5303 method time alone was ~1635 s; allow 45 minutes for that case.
[[ "$ui_timing" != true ]] || result_timeout=1800
layout_deadline=1800
if [[ "$ui_timing" == true && "$variant" == ui-geometry-1000-native ]]; then
  result_timeout=2700; layout_deadline=2700
fi
# No fixture/Agent/JVM/hook overrides through this capability wrapper.
extra=()
if [[ $# == 2 && "$1" == --prepare-dir ]]; then extra=(--prepare-dir "$2")
elif [[ $# == 1 && "$1" == --dry-run ]]; then extra=(--dry-run)
elif [[ $# != 0 ]]; then echo 'Unsupported Atlas wrapper options' >&2; exit 2
fi
id="$(bash "$root/scripts/dev/worktree-id.sh")"
bundle="$root/build/preview/$id"
agent="$bundle/turboism-agent.jar"
probe="$root/build/atlas-queue-probe/atlas-queue-probe.jar"
shopt -s nullglob
maxrects=("$root/build/worktree/$id/atlas-maxrects-bssf/libs/"*.jar)
dalsoo=("$root/build/worktree/$id/atlas-dalsoo-polygon/libs/"*.jar)
[[ ${#maxrects[@]} == 1 && ${#dalsoo[@]} == 1 && -f "$probe" && -f "$agent" ]] || { echo 'Build previewBundle, both Atlas plugin jars and package-atlas-queue-probe.sh first' >&2; exit 2; }
fixture_name="atlas_mapping_geometry_$count.cmo3"
[[ "$dataset" != circle ]] || fixture_name="atlas_mapping_$count.cmo3"
fixture="$root/test-assets/texture-atlas-layout/cmo3/$dataset/$fixture_name"
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
