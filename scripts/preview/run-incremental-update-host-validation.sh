#!/usr/bin/env bash
# Capability arguments only; common runner owns official launch, isolation and cleanup.
#
# Per-object incremental model update exact-host validation: off/probe/on legs
# on each reviewed version. The reviewed resize driver forces modeling-canvas
# re-layout/re-render (the same repaint scenario the model-update-skip wrapper
# drives); the task-local exerciser plugin samples the bridge statistics slot
# plus SDK render/jank counters and writes the terminal result file.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 2 ]; then
  echo "usage: run-incremental-update-host-validation.sh <off|probe|on> <5203|5302|5303> [run-label] [runner-options...]" >&2
  exit 2
fi
mode="$1"
version="$2"
shift 2
case "$mode" in
  off) enabled=false; probe=false ;;
  probe) enabled=true; probe=true ;;
  on) enabled=true; probe=false ;;
  *) echo "usage: run-incremental-update-host-validation.sh <off|probe|on> <5203|5302|5303> [run-label] [runner-options...]" >&2; exit 2 ;;
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
probe_jar="$repo_root/build/incremental-update-host-validation-exerciser.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

if [ ! -f "$agent_jar" ]; then
  echo "error: agent jar not found at $agent_jar; run previewBundle first" >&2
  exit 1
fi
if [ ! -f "$probe_jar" ]; then
  echo "error: probe jar not found at $probe_jar; run buildIncrementalUpdateHostProbe first" >&2
  exit 1
fi

# The generic Runner owns this local hook's task lifecycle. It snapshots the
# reviewed driver path, starts it only after the task is admitted/launched, and
# contains it alongside Cubism in the admitted scope. {FIXTURE_NAME} resolves
# after QUEUE_RUN_ID is selected, so prepared and executed tasks agree.
fixture_suffix='incremental-update.cmo3'
driver="$repo_root/scripts/preview/fps-resize-driver.sh"
[ -r "$driver" ] || { echo "error: resize driver is missing at $driver; cannot produce repaint evidence" >&2; exit 1; }

# The probe mismatch report lives inside the isolated Turboism home so it is
# collected with the run evidence; it is written only when a narrowed-epoch
# digest differs from its paired forced-full digest.
probe_options=()
ready_markers=(--ready-marker 'INCREMENTAL_UPDATE_EXERCISER_READY')
if [[ "$enabled" == true ]]; then
  ready_markers+=(--ready-marker 'TURBOISM_INCREMENTAL_UPDATE installation=COMPLETE')
fi
if [[ "$probe" == true ]]; then
  probe_options+=(--jvm-option '-Dturboism.incremental-update.probe=true')
  probe_options+=(--jvm-option '-Dturboism.incremental-update.probe.result={HOME}\logs\incremental-update-probe.jsonl')
fi

exec bash "$runner" \
  --name incremental-update \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$repo_root/build/preview/$worktree_id" \
  --agent "$agent_jar" \
  --plugin "$probe_jar:incremental-update-host-validation-exerciser.jar" \
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
  --failure-marker 'TURBOISM_INCREMENTAL_UPDATE installation=FAILED' \
  --failure-marker 'TURBOISM_INCREMENTAL_UPDATE installation=NOT_ADMITTED' \
  --result-file 'state/dev.turboism.validation.incremental-update/result.txt' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --jvm-option "-Dturboism.optimization.incrementalUpdate=$enabled" \
  --jvm-option '-Dturboism.optimization.warpPositionProjection=false' \
  --jvm-option '-Dturboism.optimization.imageArchiveReuse=false' \
  --jvm-option '-Dturboism.optimization.floatArrayParseCache=false' \
  --jvm-option '-Dturboism.optimization.textureUploadPreparation=false' \
  --jvm-option '-Dturboism.optimization.modelUpdateSkip=false' \
  "${probe_options[@]}" \
  --ready-timeout 300 \
  --result-timeout 480 \
  --exit-timeout 120 \
  "$@"
