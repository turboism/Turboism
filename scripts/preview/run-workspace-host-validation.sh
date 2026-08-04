#!/usr/bin/env bash
# Workspace adapter for the generic exact-host runner.
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: run-workspace-host-validation.sh <5302|5203> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

case "$version" in
  5302)
    fixture_src='<local-home>/Documents/测试 混合模式.cmo3'
    fixture_sha256='57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4'
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
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-workspace-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

exec bash "$runner" \
  --name workspace \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$bundle_root/plugins/workspace-validation-probe.jar:workspace-validation-probe.jar" \
  --aux-agent "$bundle_root/workspace-validation-agent.jar:workspace-validation-agent.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option '-Dturboism.workspaceValidation.mode=matrix' \
  --jvm-option '-Dturboism.validation.exitOnComplete=true' \
  --ready-marker 'Windows workspace validation probe initialized' \
  --ready-marker 'Plugin load complete' \
  --result-file 'state/workspace-host-validation.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'WORKSPACE_HOST_VALIDATION_RESULT status=FAIL' \
  --ready-timeout 300 \
  --result-timeout 900 \
  --exit-timeout 120 \
  "$@"
