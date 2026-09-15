#!/usr/bin/env bash
# Capability arguments only; common runner owns official launch, isolation and cleanup.
#
# Model-update unchanged-frame skip exact-host validation: off/probe/on legs on
# each reviewed version. The reviewed resize driver forces modeling-canvas
# re-layout/re-render (the same repaint scenario the legacy camera wrapper
# drives); the task-local exerciser plugin samples the bridge statistics slot
# plus SDK render/jank counters and writes the terminal result file.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 2 ]; then
  echo "usage: run-model-update-skip-host-validation.sh <off|probe|on> <5203|5302|5303> [run-label] [runner-options...]" >&2
  exit 2
fi
mode="$1"
version="$2"
shift 2
case "$mode" in
  off) enabled=false; probe=false ;;
  probe) enabled=true; probe=true ;;
  on) enabled=true; probe=false ;;
  *) echo "usage: run-model-update-skip-host-validation.sh <off|probe|on> <5203|5302|5303> [run-label] [runner-options...]" >&2; exit 2 ;;
esac
case "$version" in 5203|5302|5303) ;; *) echo "error: version must be 5203, 5302, or 5303" >&2; exit 2 ;; esac
turboism_select_fixture "$version" || exit 2
run_label="$mode"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
probe_jar="$repo_root/build/model-update-skip-host-validation-exerciser.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

if [ ! -f "$agent_jar" ]; then
  echo "error: agent jar not found at $agent_jar; run previewBundle first" >&2
  exit 1
fi
if [ ! -f "$probe_jar" ]; then
  echo "error: probe jar not found at $probe_jar; run buildModelUpdateSkipHostProbe first" >&2
  exit 1
fi

# The generic Runner owns this local hook's task lifecycle. It snapshots the
# reviewed driver path, starts it only after the task is admitted/launched, and
# contains it alongside Cubism in the admitted scope. {FIXTURE_NAME} resolves
# after QUEUE_RUN_ID is selected, so prepared and executed tasks agree.
fixture_suffix='model-update-skip.cmo3'
driver="$repo_root/scripts/preview/fps-resize-driver.sh"
[ -r "$driver" ] || { echo "error: resize driver is missing at $driver; cannot produce repaint evidence" >&2; exit 1; }

# The probe mismatch report lives inside the isolated Turboism home so it is
# collected with the run evidence; it is written only when a decided-skip
# digest differs from the post-update digest.
probe_options=()
ready_markers=(--ready-marker 'MODEL_UPDATE_SKIP_EXERCISER_READY')
if [[ "$enabled" == true ]]; then
  ready_markers+=(--ready-marker 'TURBOISM_MODEL_UPDATE_SKIP installation=COMPLETE')
fi
if [[ "$probe" == true ]]; then
  probe_options+=(--jvm-option '-Dturboism.model-update-skip.probe=true')
  probe_options+=(--jvm-option '-Dturboism.model-update-skip.probe.result={HOME}\logs\model-update-skip-probe.jsonl')
fi

exec bash "$runner" \
  --name model-update-skip \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$repo_root/build/preview/$worktree_id" \
  --agent "$agent_jar" \
  --plugin "$probe_jar:model-update-skip-host-validation-exerciser.jar" \
  --home-config "$repo_root/testing/host-validation/texture-upload/config.json" \
  --fixture-host "$fixture_src" \
  --fixture-name "$fixture_suffix" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --remote-pre-launch "$driver" \
  --remote-pre-launch-background \
  --remote-pre-launch-args-only \
  --remote-pre-launch-arg '{FIXTURE_NAME}' \
  "${ready_markers[@]}" \
  --failure-marker 'TURBOISM_MODEL_UPDATE_SKIP installation=FAILED' \
  --failure-marker 'TURBOISM_MODEL_UPDATE_SKIP installation=NOT_ADMITTED' \
  --result-file 'state/dev.turboism.validation.model-update-skip/result.txt' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --jvm-option "-Dturboism.optimization.modelUpdateSkip=$enabled" \
  --jvm-option '-Dturboism.optimization.warpPositionProjection=false' \
  --jvm-option '-Dturboism.optimization.imageArchiveReuse=false' \
  --jvm-option '-Dturboism.optimization.floatArrayParseCache=false' \
  --jvm-option '-Dturboism.optimization.textureUploadPreparation=false' \
  "${probe_options[@]}" \
  --ready-timeout 300 \
  --result-timeout 480 \
  --exit-timeout 120 \
  "$@"
