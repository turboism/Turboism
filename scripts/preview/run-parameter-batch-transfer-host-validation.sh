#!/usr/bin/env bash
# Parameter-batch-transfer adapter for the generic exact-host runner.
#
# Matrix per version:
#   session — the exerciser re-executes the plugin's dialog session read path
#             (bound parameters, M/C markers, labels, same-category candidates)
#             and records structured expected/actual/status evidence.
#   apply   — the exerciser applies morph rows through the public whole-binding SDK call
#             parameterBindingBatch().transferMorphClamped (all source points), and
#             keeps the existing keyform transferClamped path for the keyform lane.
# The runner records terminal PASS/FAIL from the result properties file.
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: run-parameter-batch-transfer-host-validation.sh <5302|5203> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

case "$version" in
  5302)
    fixture_src='<local-home>/TurboismValidation/parameter-batch-transfer/manual-5302/manual-pbt-5302-20260808T045249Z/fixture.cmo3'
    fixture_sha256='8b1718d2976eabffc8d85ea10343003aadea784230094e5397a41acbc17a20b8'
    ;;
  5203)
    fixture_src='<local-home>/TurboismPartValidation/part52-official/part-opacity-fixture-52-final.cmo3'
    fixture_sha256='331bbb4cbdb1287f5bd063a0661d94c2860534baa7d0f76bb055ed070a21b028'
    ;;
  *)
    echo "error: version must be 5302 or 5203" >&2
    exit 2
    ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-parameter-batch-transfer-validation"
probe_jar="$repo_root/build/parameter-batch-transfer-host-validation-probe.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

exec bash "$runner" \
  --name parameter-batch-transfer \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$bundle_root/plugins/parameter-batch-transfer.jar:parameter-batch-transfer.jar" \
  --plugin "$probe_jar:parameter-batch-transfer-host-validation-probe.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.hostVersion='$version \
  --jvm-option '-Dturboism.validation.fixtureName=fixture.cmo3' \
  --jvm-option '-Dturboism.validation.runId={TASK_ID}' \
  --ready-marker 'Plugin load complete' \
  --ready-marker 'ParameterBatchTransferPlugin enabled' \
  --ready-marker 'PBT_PROBE_READY' \
  --trigger 'state/dev.turboism.validation.parameter-batch-transfer/exerciser.flag' \
  --result-file 'state/parameter-batch-transfer-validation-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'PBT_MATRIX_RESULT status=FAIL' \
  --failure-marker 'PBT_MATRIX_RESULT status=BLOCKED' \
  --failure-marker 'PBT_PROBE_FLAG_TIMEOUT' \
  --ready-timeout 300 \
  --result-timeout 900 \
  --exit-timeout 120 \
  "$@"
