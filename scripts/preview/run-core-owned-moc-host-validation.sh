#!/usr/bin/env bash
# Standalone production SDK Core assertions under the official contained Runner.
set -euo pipefail
if [ "$#" -lt 1 ]; then
  echo 'usage: run-core-owned-moc-host-validation.sh <version-code> [run-label] [runner-options...]' >&2
  exit 2
fi
version="$1"
shift
# Tuples observed in finalized exact-host preflights; no series-based guessing.
case "$version" in
  5002|5003|5004|5005|5006|5100|5101|5102|5103) core_tuple='5.0.0' ;;
  5200|5201|5202|5203) core_tuple='5.0.256' ;;
  5300|5301|5302|5303) core_tuple='6.0.257' ;;
  5304) core_tuple='6.0.513' ;;
  *) echo "error: no observed Core tuple for $version" >&2; exit 2 ;;
esac
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/preview/host-validation-env.sh"
turboism_select_fixture "$version"
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
