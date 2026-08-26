#!/usr/bin/env bash
set -euo pipefail

# Assembles the production MCP plugin, lifecycle probe, and redacted raw HTTP client.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-mcp-validation}"
preview_root="$repo_root/build/preview/$worktree_id"
mcp_jars=("$repo_root"/build/worktree/"$worktree_id"/mcp/libs/mcp-*.jar)
probe_jar="$repo_root/build/mcp-host-validation-probe.jar"
client="$repo_root/scripts/preview/mcp-host-validation-client.py"
readme="$repo_root/scripts/preview/README-mcp-validation.md"

[ -f "$preview_root/turboism-agent.jar" ] \
  || { echo "error: preview agent not found: $preview_root/turboism-agent.jar" >&2; exit 1; }
[ "${#mcp_jars[@]}" -gt 0 ] && [ -f "${mcp_jars[0]}" ] \
  || { echo "error: MCP plugin jar not found under build/worktree/$worktree_id/mcp/libs/" >&2; exit 1; }
[ -f "$probe_jar" ] || { echo "error: MCP host probe not found: $probe_jar" >&2; exit 1; }
[ -f "$client" ] || { echo "error: MCP validation client not found: $client" >&2; exit 1; }
[ -f "$readme" ] || { echo "error: MCP validation README not found: $readme" >&2; exit 1; }

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/client"
cp "$preview_root/turboism-agent.jar" "$bundle_root/turboism-agent.jar"
cp "${mcp_jars[0]}" "$bundle_root/plugins/mcp.jar"
cp "$probe_jar" "$bundle_root/plugins/mcp-host-validation-probe.jar"
cp "$client" "$bundle_root/client/mcp-host-validation-client.py"
chmod 700 "$bundle_root/client/mcp-host-validation-client.py"
cp "$readme" "$bundle_root/README.md"

(
  cd "$bundle_root"
  sha256sum \
    turboism-agent.jar \
    plugins/mcp.jar \
    plugins/mcp-host-validation-probe.jar \
    client/mcp-host-validation-client.py \
    README.md > SHA256SUMS.txt
)

printf '[package] Windows MCP validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
