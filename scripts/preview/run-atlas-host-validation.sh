#!/usr/bin/env bash
# Fixed Geometry current-page task. Generic queued Runner owns all host lifecycle.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$root/scripts/preview/host-validation-env.sh"
[[ $# -ge 3 && "$1" == 5303 ]] || { echo 'usage: atlas 5303 run-label geometry-{100,500,1000,2500}-{native,new} [--prepare-dir DIR|--dry-run]' >&2; exit 2; }
version="$1"; label="$2"; variant="$3"; shift 3
parallel=false
case "$variant" in
  ui-circle-100-new-parallel|ui-geometry-100-new-parallel|ui-circle-500-new-parallel|ui-geometry-500-new-parallel)
    parallel=true; variant="${variant%-parallel}" ;;
esac
dataset=geometry; ui_timing=false
case "$variant" in
  ui-circle-*-native|ui-circle-*-new) dataset=circle; ui_timing=true ;;
  ui-geometry-*-native|ui-geometry-*-new) ui_timing=true ;;
esac
case "$variant" in
  geometry-100-native|geometry-100-new) count=100; hash=369c906ad47610a958e770930649f0821f616e8eb66564c39a478c41b49024ff ;;
  geometry-500-native|geometry-500-new) count=500; hash=99038495f8c7fb9ee94cf6b9ce3d60370fc0203c411ed39b0084d84ce72a683b ;;
  ui-geometry-500-native|ui-geometry-500-new) count=500; hash=99038495f8c7fb9ee94cf6b9ce3d60370fc0203c411ed39b0084d84ce72a683b ;;
  ui-circle-500-native|ui-circle-500-new) count=500; hash=54ce27647bd8d63d16fc643eb209ef2016fda0d975779b5b07943da303478b50 ;;
  ui-circle-100-native|ui-circle-100-new) count=100; hash=2866a509322496680500090cb26432b1b59fe30404e1f5e2163536c01a8dce4e ;;
  ui-circle-1000-native|ui-circle-1000-new) count=1000; hash=5a1a4d0e1f27dbffe09eafc5d5f8626776bcad35fab8fcd06c6ecc34b68959f9 ;;
  ui-circle-2500-native|ui-circle-2500-new) count=2500; hash=3cbd3d8ef91166010c90d5f96d872ef527841b36aadb9e634368cab3d159a239 ;;
  ui-geometry-100-native|ui-geometry-100-new) count=100; hash=369c906ad47610a958e770930649f0821f616e8eb66564c39a478c41b49024ff ;;
  ui-geometry-1000-native|ui-geometry-1000-new) count=1000; hash=2f64a7f5fd4f2abc4cdd0036d1943f8382f30581228ea5f87143b5b367abd3e2 ;;
  ui-geometry-2500-native|ui-geometry-2500-new) count=2500; hash=4bb38d8073cf339b32047bf186514dc7d3709cbfb90dbe665b0a5b0a76daf24b ;;
  geometry-1000-native|geometry-1000-new) count=1000; hash=2f64a7f5fd4f2abc4cdd0036d1943f8382f30581228ea5f87143b5b367abd3e2 ;;
  geometry-2500-native|geometry-2500-new) count=2500; hash=4bb38d8073cf339b32047bf186514dc7d3709cbfb90dbe665b0a5b0a76daf24b ;;
  *) echo 'Unadmitted Atlas case' >&2; exit 2 ;;
esac
implementation="${variant##*-}"
result_timeout=7500
[[ "$count" != 2500 ]] || result_timeout=21900
# UI matrix uses a 30-minute result ceiling; submit with manager hard timeout 1800 as well.
[[ "$ui_timing" != true ]] || result_timeout=1800
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
plugins=("$root/build/worktree/$id/atlas-maxrects-bssf/libs/"*.jar)
[[ ${#plugins[@]} == 1 && -f "$probe" && -f "$agent" ]] || { echo 'Build previewBundle, Atlas jar and package-atlas-queue-probe.sh first' >&2; exit 2; }
fixture_name="atlas_mapping_geometry_$count.cmo3"
[[ "$dataset" != circle ]] || fixture_name="atlas_mapping_$count.cmo3"
fixture="$root/test-assets/texture-atlas-layout/cmo3/$dataset/$fixture_name"
exec bash "$root/scripts/preview/run-cubism-host-validation.sh" \
  --name atlas --version "$version" --run-label "$label-$variant-parallel-$parallel" \
  --bundle-root "$bundle" --agent "$agent" \
  --plugin "${plugins[0]}:atlas-maxrects-bssf.jar" \
  --aux-agent "$probe:atlas-queue-probe.jar" \
  --fixture-local "$fixture" --fixture-sha256 "$hash" --require-fixture-unchanged \
  --fixture-name "$fixture_name" \
  --jvm-option "-Dturboism.validation.atlas.count=$count" \
  --jvm-option "-Dturboism.validation.atlas.implementation=$implementation" \
  --jvm-option "-Dturboism.validation.atlas.dataset=$dataset" \
  --jvm-option "-Dturboism.validation.atlas.uiTiming=$ui_timing" \
  --jvm-option "-Dturboism.validation.atlas.parallel=$parallel" \
  --jvm-option '-Dturboism.validation.atlas.fixtureName={FIXTURE_NAME}' \
  --result-file state/atlas-validation/result.txt \
  --result-timeout "$result_timeout" --exit-timeout 120 \
  "${extra[@]}"
