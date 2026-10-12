#!/usr/bin/env bash
# Standalone production SDK Core assertions under the official contained Runner.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/preview/host-validation-env.sh"
if [ "$#" -lt 1 ]; then
  echo "usage: run-core-owned-moc-host-validation.sh $(turboism_task_versions_usage core-owned-moc) [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
turboism_require_task_version core-owned-moc "$version" || exit 2
core_tuple="$(turboism_expected_core_tuple "$version")" || exit 2
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi
turboism_select_fixture "$version" || exit 2
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-mcp-validation"
exec bash "$repo_root/scripts/preview/run-cubism-host-validation.sh" \
  --name core-owned-moc --version "$version" --run-label "$run_label" \
  --bundle-root "$bundle_root" --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$bundle_root/plugins/mcp-host-validation-probe.jar:mcp-host-validation-probe.jar" \
  --fixture-host "$fixture_src" --fixture-sha256 "$fixture_sha256" --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.exitOnComplete=true' \
  --jvm-option '-Dturboism.validation.startFree=true' \
  --jvm-option '-Dturboism.validation.ownedMocOnly=true' \
  --jvm-option '-Dturboism.optimization.mesaGlThread=false' \
  --linux-env 'mesa_glthread=false' \
  --jvm-option "-Dturboism.validation.expectedCoreVersion=$core_tuple" \
  --jvm-option '-Dturboism.validation.fixture={FIXTURE}' \
  --ready-marker 'MCP host validation probe initialized' \
  --ready-marker 'Plugin load complete' \
  --result-file 'state/core-owned-moc-result.properties' \
  --result-pass-line 'status=PASS' --result-fail-line 'status=FAIL' \
  --ready-timeout 360 --result-timeout 300 --exit-timeout 240 \
  "$@"
