#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local MCP lifecycle probe and shared test-only UI close helper.
# None of the helper classes are production SDK/runtime dependencies.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
sdk_jar="$(find "build/worktree/$worktree_id/sdk/libs" -maxdepth 1 -name 'sdk-*.jar' -type f | head -1)"
if [ -z "$sdk_jar" ] || [ ! -f "$sdk_jar" ]; then
  echo "error: sdk jar not found; run :sdk:jar first" >&2
  exit 1
fi

base="validation/mcp-host-probe"
src="$base/src"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

helper_root="testing/integration-tests/src/test/java/dev/turboism/tests/plugin"
javac --release 17 -cp "$sdk_jar" -d "$out" \
  "$helper_root/WindowsHistoryNativeUiHostClose.java" \
  "$helper_root/McpValidationHostClose.java" \
  "$src/dev/turboism/validation/mcp/McpHostValidationPlugin.java"
cp -r "$src/META-INF" "$out/"

output="$repo_root/build/mcp-host-validation-probe.jar"
jar cf "$output" -C "$out" .
if ! jar tf "$output" | grep -Fq 'META-INF/turboism/plugin.json'; then
  echo "error: MCP probe jar is missing META-INF/turboism/plugin.json" >&2
  exit 1
fi
echo "[probe] $output"
sha256sum "$output"
