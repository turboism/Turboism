#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/preview/host-validation-env.sh"
[[ $# -ge 2 && "$2" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,80}$ ]] || { echo 'invalid UI validation version/label' >&2; exit 2; }
version="$1"; label="$2"; shift 2
turboism_require_task_version model-import-3d-user-entry "$version" || exit 2
options=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) options+=(--dry-run); shift ;;
    --prepare-dir) [[ $# -ge 2 ]] || exit 2; options+=(--prepare-dir "$2"); shift 2 ;;
    *) echo 'only dry-run/prepare-dir options allowed' >&2; exit 2 ;;
  esac
done
turboism_select_fixture "$version" || exit 2
: "${TURBOISM_MI3D_UI_BUILD:?explicit frozen UI validation bundle required}"
python3 -B "$repo_root/validation/model-import-3d-user-entry/verify-bundle.py" "$TURBOISM_MI3D_UI_BUILD"
bundle="$TURBOISM_MI3D_UI_BUILD/bundle"
profile="$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("profile","cube"))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")"
result_timeout=600
jvm_options=()
extra_plugins=()
extra_aux=()
extra_ready=()
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("shaderUniformCandidate",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.shaderUniformCandidate=true')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("shaderUniformProduct",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.shaderUniformProduct=true')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("editableDragTraceInput",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.dragTrace=true')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("editableDragInput",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.editableDrag=true')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("meshEditingTools",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  extra_plugins+=(--plugin "$bundle/plugins/mesh-edit-mirror-axis-enhance.jar:mesh-edit-mirror-axis-enhance.jar")
  extra_ready+=(--ready-marker 'Turboism Developer Preview started')
fi
case "$profile" in
  apple-derived-save) result_timeout=3600; jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile" --jvm-option '-Dturboism.validation.mi3d.persistenceSave=true'); fixture_src="$TURBOISM_MI3D_UI_BUILD/seed.cmo3"; fixture_sha256="$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["reopenSeed"]["sha256"])' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" ;;
  apple-derived-atlas-undo|apple-derived-export|apple-derived-reopen|apple-derived-edit|apple-source-triangulation|apple-bound-partition-reopen|apple-xyz-export|apple-xyz-export-bytes|apple-all-mesh-edit) result_timeout=3600; jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile"); fixture_src="$TURBOISM_MI3D_UI_BUILD/seed.cmo3"; fixture_sha256="$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["reopenSeed"]["sha256"])' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" ;;
  cat-vertex-save|cat-optimized-save|cat-adaptive-save|cat-cost-save) result_timeout=3600; jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile" --jvm-option '-Dturboism.validation.mi3d.persistenceSave=true');
    if [[ "$(python3 -B -c 'import json,sys; print(str(json.load(open(sys.argv[1])).get("editableTopologyInput",False)).lower())' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == true ]]; then
      jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.editableTopology=true')
    fi
    if [[ "$(python3 -B -c 'import json,sys; print(str(json.load(open(sys.argv[1])).get("externalResourceInput",False)).lower())' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == true ]]; then
      jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.externalResources=true')
    fi ;;
  cat-native-serialization)
    result_timeout=3600
    fixture_src="$TURBOISM_MI3D_UI_BUILD/seed.cmo3"
    fixture_sha256="$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["reopenSeed"]["sha256"])' "$TURBOISM_MI3D_UI_BUILD/bundle.json")"
    serialization_agent_sha="$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["files"]["turboism-agent.jar"]["sha256"])' "$TURBOISM_MI3D_UI_BUILD/bundle.json")"
    jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile" --jvm-option "-Dturboism.validation.mi3d.serializationSeedSha=$fixture_sha256" --jvm-option "-Dturboism.validation.mi3d.serializationAgentSha=$serialization_agent_sha"
      --jvm-option '-Dturboism.meshInterpolation.enabled=true' --jvm-option '-Dturboism.meshInterpolation.coefficients=true'
      --jvm-option '-Dturboism.meshInterpolation.serializationProjection=true' --jvm-option '-Dturboism.meshInterpolation.serializationStreaming=true'
      --jvm-option '-Dturboism.meshInterpolation.serializationGridProjection=true' --jvm-option '-Dturboism.meshInterpolation.projectionDiagnostic=true')
    extra_aux+=(--aux-agent "$bundle/agents/cat-native-serialization-agent.jar:cat-native-serialization-agent.jar") ;;
  cat-default-serialization)
    result_timeout=3600
    fixture_src="$TURBOISM_MI3D_UI_BUILD/seed.cmo3"
    fixture_sha256="$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["reopenSeed"]["sha256"])' "$TURBOISM_MI3D_UI_BUILD/bundle.json")"
    serialization_agent_sha="$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["files"]["turboism-agent.jar"]["sha256"])' "$TURBOISM_MI3D_UI_BUILD/bundle.json")"
    jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile" --jvm-option "-Dturboism.validation.mi3d.serializationSeedSha=$fixture_sha256" --jvm-option "-Dturboism.validation.mi3d.serializationAgentSha=$serialization_agent_sha"
      --jvm-option '-Dturboism.validation.mi3d.defaultSerialization=true' --jvm-option '-Dturboism.validation.mi3d.persistenceSave=true')
    extra_aux+=(--aux-agent "$bundle/agents/cat-native-serialization-agent.jar:cat-native-serialization-agent.jar") ;;
  cat-vertex-drag|cat-optimized-drag|cat-optimized-short-drag|cat-adaptive-short-drag|cat-cost-drag) result_timeout=3600; jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile" --jvm-option '-Dturboism.meshInterpolation.enabled=true'); fixture_src="$TURBOISM_MI3D_UI_BUILD/seed.cmo3"; fixture_sha256="$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["reopenSeed"]["sha256"])' "$TURBOISM_MI3D_UI_BUILD/bundle.json")"; extra_aux+=(--aux-agent "$bundle/agents/cat-native-drag-agent.jar:cat-native-drag-agent.jar"); ;;
  cat-vertex-drag-layout) result_timeout=3600; jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile" --jvm-option '-Dturboism.meshInterpolation.enabled=true'); fixture_src="$TURBOISM_MI3D_UI_BUILD/seed.cmo3"; fixture_sha256="$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["reopenSeed"]["sha256"])' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" ;;
  cat-optimized-reopen|cat-vertex-reopen|cat-vertex-bank|cat-vertex-edit) result_timeout=3600; jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile"); fixture_src="$TURBOISM_MI3D_UI_BUILD/seed.cmo3"; fixture_sha256="$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["reopenSeed"]["sha256"])' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" ;;
  cat-vertex-menu|cat-vertex-frames) result_timeout=3600; jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile") ;;
  interpolation-witness-save) jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile" --jvm-option '-Dturboism.validation.mi3d.persistenceSave=true') ;;
  interpolation-witness-reopen)
    jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile")
    fixture_src="$TURBOISM_MI3D_UI_BUILD/seed.cmo3"
    fixture_sha256="$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["reopenSeed"]["sha256"])' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" ;;
  interpolation-witness) jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile") ;;
  apple-layered) result_timeout=3600 ;;
  plane-menu|seam-menu) result_timeout=1200; jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile") ;;
  plane-menu-save|seam-menu-save)
    result_timeout=1200
    jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile")
    jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.persistenceSave=true') ;;
  plane-menu-reopen|seam-menu-reopen|plane-menu-atlas-save|seam-menu-atlas-save|plane-menu-atlas-reopen|seam-menu-atlas-reopen)
    result_timeout=1200
    jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile")
    fixture_src="$TURBOISM_MI3D_UI_BUILD/seed.cmo3"
    fixture_sha256="$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["reopenSeed"]["sha256"])' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" ;;
  menu-reject) jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.mode=menu-reject') ;;
  apple-menu-reopen-compact|apple-menu-export-compact|apple-menu-atlas-save-compact|apple-menu-atlas-reopen-compact|apple-menu-editability-compact)
    result_timeout=3600
    jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile")
    fixture_src="$TURBOISM_MI3D_UI_BUILD/seed.cmo3"
    fixture_sha256="$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["reopenSeed"]["sha256"])' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" ;;
  apple-menu-placement-compact)
    result_timeout=3600
    jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile") ;;
  apple-menu-save|apple-menu-save-compact)
    result_timeout=3600
    jvm_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$profile")
    jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.persistenceSave=true') ;;
esac
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("nativeImportWatchdog",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  extra_aux+=(--aux-agent "$bundle/agents/cat-import-watchdog-agent.jar:cat-import-watchdog-agent.jar")
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("meshStartIndex",0))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == 26 ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.meshStartIndex=26')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("meshEndIndex",0))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == 27 ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.meshEndIndex=27')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("equivalentSlotProbe",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.equivalentSlotProbe=true')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("partitionBoundProbe",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.partitionBoundProbe=true' --jvm-option '-Dturboism.validation.mi3d.persistenceSave=true')
fi
if [[ "$profile" == apple-menu-atlas-save-compact || "$profile" == plane-menu-atlas-save || "$profile" == seam-menu-atlas-save ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.persistenceSave=true')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("originalMeshReopen",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.originalMeshReopen=true')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("meshEditingProbe",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.meshEditingProbe=true')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("originalMeshEditing",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.originalMeshEditing=true')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("meshEditingCommitSave",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.meshEditingCommitSave=true' --jvm-option '-Dturboism.validation.mi3d.persistenceSave=true')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("xyzProbe",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.xyzProbe=true')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("placementProbe",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.placementProbe=true')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("xyzSaveOnly",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.xyzSaveOnly=true')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("xyzSave",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.mode=cube-xyz-menu-save')
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.persistenceSave=true')
fi
if [[ "$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1])).get("xyzReopen",False))' "$TURBOISM_MI3D_UI_BUILD/bundle.json")" == True ]]; then
  jvm_options+=(--jvm-option '-Dturboism.validation.mi3d.mode=cube-xyz-menu-reopen')
  fixture_src="$TURBOISM_MI3D_UI_BUILD/seed.cmo3"
  fixture_sha256="$(python3 -B -c 'import json,sys; print(json.load(open(sys.argv[1]))["reopenSeed"]["sha256"])' "$TURBOISM_MI3D_UI_BUILD/bundle.json")"
fi
exec bash "$repo_root/scripts/preview/run-cubism-host-validation.sh" \
  --name model-import-3d-user-entry --version "$version" --run-label "$label" \
  --bundle-root "$bundle" --agent "$bundle/turboism-agent.jar" \
  --plugin "$bundle/plugins/model-import-3d.jar:model-import-3d.jar" \
  --plugin "$bundle/plugins/model-import-3d-user-entry-probe.jar:model-import-3d-user-entry-probe.jar" \
  "${extra_plugins[@]}" \
  "${extra_aux[@]}" \
  --home-file "$TURBOISM_MI3D_UI_BUILD/bundle.json:state/mi3d-ui-build/bundle.json" \
  --fixture-host "$fixture_src" --fixture-sha256 "$fixture_sha256" \
  --fixture-name mi3d-ui-readonly-seed.cmo3 --require-fixture-unchanged \
  --jvm-option "-Dturboism.validation.hostVersion=$version" \
  --jvm-option '-Dturboism.validation.runId={TASK_ID}' \
  "${jvm_options[@]}" \
  --windows-env 'TURBOISM_MI3D_EXIT_DIAGNOSTIC=1' \
  --ready-marker 'Plugin load complete' --ready-marker 'MI3D_UI_READY' \
  "${extra_ready[@]}" \
  --trigger 'state/dev.turboism.validation.model-import-3d-user-entry/exerciser.flag' \
  --result-file 'state/dev.turboism.validation.model-import-3d-user-entry/result.properties' \
  --result-pass-line 'status=PASS' --result-fail-line 'status=FAIL' \
  --failure-marker 'MI3D_UI_FAILED' --failure-marker 'MI3D_UI_REPORT_FAILED' \
  --ready-timeout 300 --result-timeout "$result_timeout" --exit-timeout 120 "${options[@]}"
