#!/usr/bin/env bash
# Parameter/editor-object adapter for the generic exact-host runner.
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: run-parameter-host-validation.sh <5302|5203> [mode] [run-label] [runner-options...]" >&2
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
  matrix|statistics-read|binding-read|binding-matrix|parameter-menu-smoke|persist-write|persist-read|plugin-scope-close|document-close|native-control-background|native-control-background-document-close|native-control-background-persist-write|native-control-background-persist-reopen|native-control-background-persist-final) ;;
  *)
    echo "error: unsupported validation mode: $mode" >&2
    exit 2
    ;;
esac

case "$version" in
  5302)
    fixture_src='/home/local-user/Documents/测试 混合模式.cmo3'
    fixture_sha256='57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4'
    ;;
  5203)
    fixture_src='/home/local-user/TurboismPartValidation/part52-official/part-opacity-fixture-52-final.cmo3'
    fixture_sha256='331bbb4cbdb1287f5bd063a0661d94c2860534baa7d0f76bb055ed070a21b028'
    ;;
  *)
    echo "error: version must be 5302 or 5203" >&2
    exit 2
    ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-parameter-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

fixture_policy=(--require-fixture-unchanged)
extra_jvm_options=()
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
  --result-timeout 900 \
  --exit-timeout 120 \
  "$@"
