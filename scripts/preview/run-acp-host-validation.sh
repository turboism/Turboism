#!/usr/bin/env bash
# ACP adapter for the generic exact-host runner.
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: run-acp-host-validation.sh <5203|5302|5303> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/preview/host-validation-env.sh"
turboism_select_fixture "$version"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-acp-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
seed="$repo_root/testing/host-validation/acp/settings-$version.properties"

case "$version" in
  5203) agent_java='C:/Program Files/Live2D Cubism 5.2/app/jre/bin/java.exe' ;;
  5302) agent_java='C:/Program Files/Live2D Cubism 5.3/app/jre/bin/java.exe' ;;
  5303) agent_java='C:/Program Files/Live2D Cubism 5.3.03/app/jre/bin/java.exe' ;;
  *)
    echo "unsupported version: $version" >&2
    exit 2
    ;;
esac
[ -f "$seed" ] || { echo "error: ACP settings seed not found: $seed" >&2; exit 1; }

exec bash "$runner" \
  --name acp \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$bundle_root/plugins/mcp.jar:mcp.jar" \
  --plugin "$bundle_root/plugins/acp.jar:acp.jar" \
  --plugin "$bundle_root/plugins/acp-host-validation-probe.jar:acp-host-validation-probe.jar" \
  --home-file "$seed:config/dev.turboism.plugin.acp/settings.properties" \
  --home-file "$bundle_root/acp-validation/acp-fake-agent.jar:acp-validation/acp-fake-agent.jar" \
  --home-file "$bundle_root/acp-validation/agent.properties:acp-validation/agent.properties" \
  --windows-env "TURBOISM_ACP_FAKE_RUN_ID={TASK_ID}" \
  --fixture-host "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option '-Dturboism.acp.validation.bridge=true' \
  --jvm-option '-Dturboism.acp.validation.bridgeClassPath={HOME}\acp-validation\acp-fake-agent.jar' \
  --jvm-option '-Dturboism.acp.validation.bridgeConfig={HOME}\acp-validation\agent.properties' \
  --jvm-option '-Dturboism.mcp.port=0' \
  --jvm-option '-Dturboism.mcp.requestsPerMinute=600' \
  --jvm-option '-Dturboism.validation.exitOnComplete=true' \
  --ready-marker 'ACP host validation probe initialized' \
  --ready-marker 'Turboism MCP server started on the local loopback interface' \
  --ready-marker 'Turboism ACP enabled' \
  --ready-marker 'Plugin load complete' \
  --result-file 'state/acp-host-validation.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'Turboism MCP startup failed' \
  --ready-timeout 360 \
  --result-timeout 900 \
  --exit-timeout 240 \
  "$@"
