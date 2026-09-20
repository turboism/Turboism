#!/usr/bin/env bash
# MCP adapter for the generic exact-host runner.
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: run-mcp-host-validation.sh <5203|5302|5303> [run-label] [runner-options...]" >&2
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
client_python="$(python3 -I -c 'import pathlib, sys; print(pathlib.Path(sys.executable).resolve())')"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-mcp-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

close_options=()
# The reviewed 5302 close route uses Alt+F4; focus tracking is task/fixture-scoped.
if [ "$version" = 5302 ]; then close_options+=(--focus-editor-window); fi

exec bash "$runner" \
  --name mcp \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$bundle_root/plugins/mcp.jar:mcp.jar" \
  --plugin "$bundle_root/plugins/mcp-host-validation-probe.jar:mcp-host-validation-probe.jar" \
  --client-script "$repo_root/scripts/preview/mcp-host-validation-client.py:mcp-host-validation-client.py" \
  --client-python "$client_python" \
  --fixture-host "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.exitOnComplete=true' \
  --jvm-option '-Dturboism.mcp.port=0' \
  --jvm-option '-Dturboism.mcp.requestsPerMinute=600' \
  --ready-marker 'MCP host validation probe initialized' \
  --ready-marker 'Turboism MCP server started on the local loopback interface' \
  --ready-marker 'Plugin load complete' \
  --result-file 'state/mcp-host-validation.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'Turboism MCP startup failed' \
  --ready-timeout 360 \
  --result-timeout 900 \
  --exit-timeout 240 \
  "${close_options[@]}" \
  "$@"
