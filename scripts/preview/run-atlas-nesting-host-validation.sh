#!/usr/bin/env bash
# Fixed nesting/BSSF comparison through the existing managed host queue.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$root/scripts/preview/host-validation-env.sh"
[[ $# -ge 3 ]] || { echo 'usage: atlas-nesting 5303 run-label {geometry-100,geometry-500,circle-500,heavy}-{nesting,bssf}' >&2; exit 2; }
version="$1"; label="$2"; variant="$3"; shift 3
turboism_require_task_version atlas-nesting "$version" || exit 2
dataset="${variant%-*}"; implementation="${variant##*-}"
case "$implementation" in nesting) ;; bssf) implementation=new ;; *) exit 2 ;; esac
case "$dataset" in
  geometry-100)
    count=100; dataset=geometry; fixture="$root/test-assets/texture-atlas-layout/cmo3/geometry/atlas_mapping_geometry_100.cmo3"
    hash=369c906ad47610a958e770930649f0821f616e8eb66564c39a478c41b49024ff ;;
  geometry-500)
    count=500; dataset=geometry; fixture="$root/test-assets/texture-atlas-layout/cmo3/geometry/atlas_mapping_geometry_500.cmo3"
    hash=99038495f8c7fb9ee94cf6b9ce3d60370fc0203c411ed39b0084d84ce72a683b ;;
  circle-500)
    count=500; dataset=circle; fixture="$root/test-assets/texture-atlas-layout/cmo3/circle/atlas_mapping_500.cmo3"
    hash=54ce27647bd8d63d16fc643eb209ef2016fda0d975779b5b07943da303478b50 ;;
  heavy)
    turboism_require_env TURBOISM_HOST_VALIDATION_FIXTURE_HEAVY 'heavy model fixture path' || exit 2
    count=0; fixture="$TURBOISM_HOST_VALIDATION_FIXTURE_HEAVY"
    hash=029e9a4ea13f03afdf956b63f6ee1dfd663bd9046c602b786d359bd1d0c7f80c ;;
  *) echo 'Unadmitted nesting fixture' >&2; exit 2 ;;
esac
extra=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --prepare-dir) extra+=("$1" "$2"); shift 2 ;;
    --dry-run) extra+=("$1"); shift ;;
    *) echo 'Unsupported nesting wrapper option' >&2; exit 2 ;;
  esac
done
id="$(bash "$root/scripts/dev/worktree-id.sh")"
shopt -s nullglob
nesting=("$root/build/worktree/$id/atlas-bitmask-nesting/libs/"*.jar)
bssf=("$root/build/worktree/$id/atlas-maxrects-bssf/libs/"*.jar)
agent="$root/build/preview/$id/turboism-agent.jar"
probe="$root/build/atlas-queue-probe/atlas-queue-probe.jar"
capture="$root/build/nesting-capture-probe/nesting-capture-probe.jar"
[[ ${#nesting[@]} == 1 && ${#bssf[@]} == 1 && -f "$agent" && -f "$probe" && -f "$capture" ]] \
  || { echo 'Build previewBundle, both plugin jars, queue probe and nesting capture probe first' >&2; exit 2; }
fixture_name="$(basename "$fixture")"
[[ "$dataset" != heavy ]] || fixture_name=heavy.cmo3
exec bash "$root/scripts/preview/run-cubism-host-validation.sh" \
  --name atlas-nesting --version "$version" --run-label "$label-$variant" \
  --bundle-root "$root/build/preview/$id" --agent "$agent" \
  --plugin "${bssf[0]}:atlas-maxrects-bssf.jar" \
  --plugin "${nesting[0]}:atlas-bitmask-nesting.jar" \
  --plugin "$capture:nesting-capture-probe.jar" \
  --aux-agent "$probe:atlas-queue-probe.jar" \
  --fixture-local "$fixture" --fixture-sha256 "$hash" --require-fixture-unchanged --fixture-name "$fixture_name" \
  --jvm-option "-Dturboism.validation.atlas.count=$count" \
  --jvm-option "-Dturboism.validation.atlas.implementation=$implementation" \
  --jvm-option "-Dturboism.validation.atlas.dataset=$dataset" \
  --jvm-option '-Xmx4g' \
  --jvm-option '-Dturboism.validation.atlas.uiTiming=true' \
  --jvm-option '-Dturboism.validation.atlas.parallel=false' \
  --jvm-option '-Dturboism.validation.atlas.layoutDeadlineSeconds=120' \
  --jvm-option '-Dturboism.validation.atlas.fixtureName={FIXTURE_NAME}' \
  --ready-timeout 600 --result-file state/atlas-validation/result.txt --result-timeout 600 --exit-timeout 120 \
  "${extra[@]}"
