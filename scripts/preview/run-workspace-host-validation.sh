#!/usr/bin/env bash
# Workspace adapter for the generic exact-host runner.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-workspace-host-validation.sh <5000|5001|5002|5003|5004|5005|5006|5100|5101|5102|5103|5200|5201|5202|5203|5300|5301|5302|5303|5304> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
case "$version" in
  5000|5001|5002|5003|5004|5005|5006|5100|5101|5102|5103|5200|5201|5202|5203|5300|5301|5302|5303|5304) ;;
  *)
    echo "error: unsupported version: $version" >&2
    exit 2
    ;;
esac
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture "$version" || exit 2

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
