#!/usr/bin/env bash
# Parameter/editor-object adapter for the generic exact-host runner.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-parameter-host-validation.sh <5203|5302|5303> [mode] [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
mode='matrix'
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  mode="$1"
  shift
fi
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

case "$mode" in
  matrix|model-edit-level|wave1|statistics-read|binding-read|binding-matrix|parameter-menu-smoke|persist-write|persist-read|plugin-scope-close|document-close|native-control-background|native-control-background-document-close|native-control-background-persist-write|native-control-background-persist-reopen|native-control-background-persist-final|perf-observe|native-baseline|native-tuned|native-tuned2) ;;
  *)
    echo "error: unsupported validation mode: $mode" >&2
    exit 2
    ;;
esac

turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-parameter-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

if [ "$mode" = 'native-baseline' ] || [ "$mode" = 'native-tuned' ] || [ "$mode" = 'native-tuned2' ]; then
  # Host-only comparison leg: a no-op premain stub satisfies the runner's
  # --agent contract without loading the Turboism runtime or any plugin, so
  # the JVM is effectively stock Cubism. No runtime log is produced, so no
  # ready markers are waited on and the result-file wait is the measurement
  # window itself; the run intentionally ends in "result timeout" and the
  # containment cleanup owns process teardown. JFR duration is bounded below
  # the window so the dump lands even though the JVM is killed afterwards.
  native_tuned_options=()
  if [ "$mode" = 'native-tuned' ]; then
    # Direction-A JVM flag A/B: same stock host, tuned GC/heap flags. Target
    # the two n2 findings: a 1.4s max GC pause during heavy-model load and
    # ~3GB of committed-but-idle heap in steady state.
    native_tuned_options=(
      --jvm-option '-XX:MaxGCPauseMillis=100'
      --jvm-option '-XX:+UseStringDeduplication'
      --jvm-option '-XX:MinHeapFreeRatio=10'
      --jvm-option '-XX:MaxHeapFreeRatio=20'
      --jvm-option '-XX:+G1PeriodicGCInvokesConcurrent'
      --jvm-option '-XX:G1PeriodicGCInterval=10000'
    )
  elif [ "$mode" = 'native-tuned2' ]; then
    # Conservative v2 after n3 regressed (193 GCs / 23.8s pauses): only
    # mechanism-targeted flags. DisableExplicitGC kills the three ~140ms
    # System.gc() pauses seen in n2; dedup is memory-only.
    native_tuned_options=(
      --jvm-option '-XX:+DisableExplicitGC'
      --jvm-option '-XX:+UseStringDeduplication'
    )
  fi
  exec bash "$runner" \
    --name parameter \
    --version "$version" \
    --run-label "$run_label-$mode" \
    --bundle-root "$bundle_root" \
    --agent "$bundle_root/native-stub-agent.jar" \
    --plugin "$bundle_root/native-stub-agent.jar:stub.jar" \
    --fixture-remote "$fixture_src" \
    --fixture-sha256 "$fixture_sha256" \
    --require-fixture-unchanged \
    --jvm-option '-XX:StartFlightRecording=duration=560s,filename={HOME}\logs\native-observe.jfr' \
    "${native_tuned_options[@]}" \
    --result-file 'state/host-validation-result.properties' \
    --result-pass-line 'status=PASS' \
    --result-fail-line 'status=FAIL' \
    --ready-timeout 60 \
    --result-timeout 700 \
    --exit-timeout 30 \
    "$@"
fi

fixture_policy=(--require-fixture-unchanged)
extra_jvm_options=()
result_timeout=900
if [ "$mode" = 'perf-observe' ]; then
  # Heavy-model runs can spend ~20min in EDT-starved await before measuring.
  result_timeout=2100
fi
case "$mode" in
  persist-write|native-control-background-persist-write|native-control-background-persist-reopen|native-control-background-persist-final)
    # Persistence validation may intentionally save the copied fixture.
    fixture_policy=()
    ;;
esac
case "$mode" in
  native-control-background*)
    extra_jvm_options+=(--jvm-option '-Dturboism.editorObjectValidation.trace=true')
    ;;
esac
case "$mode" in
  native-control-background-persist-*)
    extra_jvm_options+=(--jvm-option '-Dturboism.validation.fixture={FIXTURE}')
    ;;
esac

exec bash "$runner" \
  --name parameter \
  --version "$version" \
  --run-label "$run_label-$mode" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$bundle_root/plugins/parameter.jar:parameter.jar" \
  --plugin "$bundle_root/plugins/parameter-validation-probe.jar:parameter-validation-probe.jar" \
  --plugin "$bundle_root/plugins/editor-object-peer-validation-probe.jar:editor-object-peer-validation-probe.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  "${fixture_policy[@]}" \
  --jvm-option "-Dturboism.editorObjectValidation.mode=$mode" \
  --jvm-option '-Dturboism.validation.exitOnComplete=true' \
  "${extra_jvm_options[@]}" \
  --ready-marker 'Windows parameter validation probe initialized' \
  --ready-marker 'Plugin load complete' \
  --result-file 'state/host-validation-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'HOST_VALIDATION_RESULT status=FAIL' \
  --ready-timeout 300 \
  --result-timeout "$result_timeout" \
  --exit-timeout 120 \
  "$@"
