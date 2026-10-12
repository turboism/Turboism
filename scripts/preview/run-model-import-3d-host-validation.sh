#!/usr/bin/env bash
# N2 parameters only; generic Runner owns queue admission, official BAT and scope lifecycle.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/preview/host-validation-env.sh"
if [[ "${1:-}" == --help ]]; then
  printf 'usage: %s %s <run-label> [--dry-run | --prepare-dir <new-dir>]\n' "$0" "$(turboism_task_versions_usage model-import-3d)"
  printf 'Requires TURBOISM_MI3D_BUILD_RUN: explicit build-host.py run directory, not newest discovery.\n'
  exit 0
fi
[[ $# -ge 2 && "$2" =~ ^[A-Za-z0-9][A-Za-z0-9_-]{0,80}$ ]] || { echo 'invalid N2 version/run label' >&2; exit 2; }
version="$1"; run_label="$2"; shift 2
turboism_require_task_version model-import-3d "$version" || exit 2
options=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) options+=(--dry-run); shift ;;
    --prepare-dir) [[ $# -ge 2 ]] || exit 2; options+=(--prepare-dir "$2"); shift 2 ;;
    *) echo 'only dry-run/prepare-dir may be supplied; safety flags are fixed' >&2; exit 2 ;;
  esac
done
turboism_select_fixture "$version" || exit 2
: "${TURBOISM_MI3D_BUILD_RUN:?explicit N2 build run is required}"
# T014: TURBOISM_MI3D_MODE selects the probe leg. persistence-save adds the
# task-pinned write grant opt-in; persistence-reopen replaces the read-only
# seed with the saved CMO3 from a prior save job (TURBOISM_MI3D_REOPEN_CMO3).
mode="${TURBOISM_MI3D_MODE:-normal}"
mode_options=()
result_timeout=600
case "$mode" in
  normal) ;;
  source-warp-candidate|source-warp-textured|canonical-warp-textured|canonical-warp-layered-textured)
    result_timeout=3600
    mode_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$mode") ;;
  warp-authoring)
    mode_options+=(--jvm-option '-Dturboism.validation.mi3d.mode=warp-authoring') ;;
  plane-geometry|plane-seam-geometry|plane-render|plane-seam-render)
    # Native bounded-Undo sweep measured ~751s for 1958/5841 poses.
    # Preserve the complete inventory; allow headroom above the ~2241s estimate.
    result_timeout=3600
    mode_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$mode") ;;
  sheet-geometry|sheet-render)
    # Two evaluated meshes and 6441 poses require a separate bounded budget.
    result_timeout=5400
    mode_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$mode") ;;
  whole-import)
    mode_options+=(--jvm-option '-Dturboism.validation.mi3d.mode=whole-import') ;;
  cube-render)
    mode_options+=(--jvm-option '-Dturboism.validation.mi3d.mode=cube-render') ;;
  cube-geometry)
    mode_options+=(--jvm-option '-Dturboism.validation.mi3d.mode=cube-geometry') ;;
  joint-pose)
    mode_options+=(--jvm-option '-Dturboism.validation.mi3d.mode=joint-pose') ;;
  persistence-save|cube-persistence-save)
    mode_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$mode"
                   --jvm-option '-Dturboism.validation.mi3d.persistenceSave=true') ;;
  persistence-reopen|cube-persistence-reopen|cube-atlas|cube-export|cube-atlas-save|cube-atlas-reopen)
    [[ -n "${TURBOISM_MI3D_REOPEN_CMO3:-}" && -f "$TURBOISM_MI3D_REOPEN_CMO3" ]] \
      || { echo 'TURBOISM_MI3D_REOPEN_CMO3 must be the Job A saved .cmo3 file' >&2; exit 2; }
    fixture_src="$TURBOISM_MI3D_REOPEN_CMO3"
    fixture_sha256="$(sha256sum "$fixture_src" | cut -d' ' -f1)"
    mode_options+=(--jvm-option "-Dturboism.validation.mi3d.mode=$mode")
    if [[ "$mode" == cube-atlas-save ]]; then
      mode_options+=(--jvm-option '-Dturboism.validation.mi3d.persistenceSave=true')
    fi ;;
  *) echo 'invalid TURBOISM_MI3D_MODE (normal|plane-geometry|plane-seam-geometry|plane-render|plane-seam-render|sheet-geometry|sheet-render|joint-pose|cube-geometry|cube-render|whole-import|persistence-save|persistence-reopen|cube-persistence-save|cube-persistence-reopen|cube-atlas|cube-export|cube-atlas-save|cube-atlas-reopen)' >&2; exit 2 ;;
esac
# T014/r23: opt-in official-BAT return-code witness for the persistence legs.
# Only an explicit =1 passes through as --windows-env; every other value and
# non-persistence modes are rejected before any runner or host work starts.
case "${TURBOISM_MI3D_EXIT_DIAGNOSTIC:-}" in
  '') ;;
  1)
    case "$mode" in
      plane-geometry|plane-seam-geometry|plane-render|plane-seam-render|sheet-geometry|sheet-render|persistence-save|persistence-reopen|cube-persistence-save|cube-persistence-reopen|cube-atlas|cube-export|cube-atlas-save|cube-atlas-reopen) ;;
      *) echo 'TURBOISM_MI3D_EXIT_DIAGNOSTIC requires TURBOISM_MI3D_MODE persistence-save or persistence-reopen' >&2; exit 2 ;;
    esac
    mode_options+=(--windows-env 'TURBOISM_MI3D_EXIT_DIAGNOSTIC=1') ;;
  *) echo 'invalid TURBOISM_MI3D_EXIT_DIAGNOSTIC (unset or exactly 1)' >&2; exit 2 ;;
esac
# Validate immutable build inputs before the Runner can prepare or execute anything.
PYTHONDONTWRITEBYTECODE=1 python3 "$repo_root/validation/model-import-3d-host-probe/verify-host.py" \
  --build-run "$TURBOISM_MI3D_BUILD_RUN" --build-only
bundle="$TURBOISM_MI3D_BUILD_RUN/bundle"
provenance="$TURBOISM_MI3D_BUILD_RUN/provenance"
exec bash "$repo_root/scripts/preview/run-cubism-host-validation.sh" \
  --name model-import-3d-n2 --version "$version" --run-label "$run_label" \
  --bundle-root "$bundle" --agent "$bundle/turboism-agent.jar" \
  --plugin "$bundle/plugins/model-import-3d-host-probe.jar:model-import-3d-host-probe.jar" \
  --home-dir "$provenance:state/mi3d-n2-build" \
  --fixture-host "$fixture_src" --fixture-sha256 "$fixture_sha256" \
  --fixture-name mi3d-readonly-seed.cmo3 --require-fixture-unchanged \
  --jvm-option "-Dturboism.validation.hostVersion=$version" \
  --jvm-option '-Dturboism.validation.runId={TASK_ID}' \
  "${mode_options[@]}" \
  --ready-marker 'Plugin load complete' --ready-marker 'MI3D_N2_READY' \
  --trigger 'state/dev.turboism.validation.model-import-3d/exerciser.flag' \
  --result-file 'state/dev.turboism.validation.model-import-3d/result.properties' \
  --result-pass-line 'status=PASS' --result-fail-line 'status=FAIL' \
  --failure-marker 'MI3D_N2_FAILED' --failure-marker 'MI3D_N2_REPORT_FAILED' \
  --ready-timeout 300 --result-timeout "$result_timeout" --exit-timeout 120 "${options[@]}"
