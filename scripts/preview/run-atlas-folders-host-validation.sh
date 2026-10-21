#!/usr/bin/env bash
# Exact-host folder layout UI validation; the shared Runner owns all host lifecycle.
set -euo pipefail
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"
version="${1:?version}"; shift
label="${1:?run-label}"; shift
algorithm="${1:?algorithm}"; shift
[[ "$algorithm" == maxrects || "$algorithm" == dalsoo || "$algorithm" == reopen ]] || exit 2
turboism_require_task_version atlas-folders "$version"
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
id="$(bash "$root/scripts/dev/worktree-id.sh")"
shopt -s nullglob
maxrects=("$root/build/worktree/$id/atlas-maxrects-bssf/libs/"*.jar)
dalsoo=("$root/build/worktree/$id/atlas-dalsoo-polygon/libs/"*.jar)
[[ ${#maxrects[@]} == 1 && ${#dalsoo[@]} == 1 ]] || exit 2
fixture="${TURBOISM_ATLAS_FOLDERS_FIXTURE:-$root/test-assets/texture-atlas-layout/cmo3/geometry/atlas_mapping_geometry_100.cmo3}"
fixture_hash=369c906ad47610a958e770930649f0821f616e8eb66564c39a478c41b49024ff
extra=()
if [[ "$algorithm" == reopen ]]; then
  fixture="${TURBOISM_ATLAS_FOLDERS_SAVED:?saved model path}"
  fixture_hash="$(sha256sum "$fixture" | cut -d' ' -f1)"
  extra+=(--home-file "${TURBOISM_ATLAS_FOLDERS_EXPECTED:?expected digest path}:state/atlas-folders/expected.sha256"
    --jvm-option '-Dturboism.validation.folders.reopen=true')
fi
exec bash "$root/scripts/preview/run-cubism-host-validation.sh" \
  --name atlas-folders --version "$version" --run-label "$label-$algorithm" \
  --bundle-root "$root/build/preview/$id" --agent "$root/build/preview/$id/turboism-agent.jar" \
  --plugin "${maxrects[0]}:atlas-maxrects-bssf.jar" \
  --plugin "${dalsoo[0]}:atlas-dalsoo-polygon.jar" \
  --plugin "$root/build/folder-atlas-host-probe.jar:folder-atlas-host-probe.jar" \
  --aux-agent "$root/build/native-startup-assistance-agent.jar:native-startup-assistance-agent.jar" \
  --jvm-option '-Dturboism.validation.startupAssistance=true' \
  --jvm-option '-Dturboism.optimization.mesaGlThread=false' --linux-env 'mesa_glthread=false' \
  --fixture-local "$fixture" --fixture-sha256 "$fixture_hash" --fixture-name folder-layout.cmo3 \
  --require-fixture-unchanged \
  --jvm-option "-Dturboism.validation.folders.algorithm=$algorithm" \
  --jvm-option '-Dturboism.validation.folders.fixture={FIXTURE}' \
  --ready-marker 'FOLDER_ATLAS_PROBE_READY' --ready-marker 'Plugin load complete' \
  --trigger state/atlas-folders/go.flag --result-file state/atlas-folders/result.properties \
  --result-pass-line 'status=PASS' --result-fail-line 'status=FAIL' \
  --ready-timeout 300 --result-timeout 600 --exit-timeout 120 \
  "${extra[@]}" "$@"
