#!/usr/bin/env bash
set -euo pipefail

# Assembles the production MCP and ACP plugins, the ACP lifecycle probe, and the
# test-only fake agent for the `acp` exact-host validation capability.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-acp-validation}"
preview_root="$repo_root/build/preview/$worktree_id"
mcp_jars=("$repo_root"/build/worktree/"$worktree_id"/mcp/libs/mcp-*.jar)
acp_jars=("$repo_root"/build/worktree/"$worktree_id"/acp/libs/acp-*.jar)
probe_jar="$repo_root/build/acp-host-validation-probe.jar"
fake_agent_jar="$repo_root/build/acp-fake-agent.jar"
readme="$repo_root/scripts/preview/README-acp-validation.md"

[ -f "$preview_root/turboism-agent.jar" ] \
  || { echo "error: preview agent not found: $preview_root/turboism-agent.jar" >&2; exit 1; }
[ "${#mcp_jars[@]}" -gt 0 ] && [ -f "${mcp_jars[0]}" ] \
  || { echo "error: MCP plugin jar not found under build/worktree/$worktree_id/mcp/libs/" >&2; exit 1; }
[ "${#acp_jars[@]}" -gt 0 ] && [ -f "${acp_jars[0]}" ] \
  || { echo "error: ACP plugin jar not found under build/worktree/$worktree_id/acp/libs/" >&2; exit 1; }
[ -f "$probe_jar" ] || { echo "error: ACP host probe not found: $probe_jar; run validation/acp-host-probe/build.sh" >&2; exit 1; }
[ -f "$fake_agent_jar" ] || { echo "error: fake agent not found: $fake_agent_jar; run validation/acp-fake-agent/build.sh" >&2; exit 1; }
[ -f "$readme" ] || { echo "error: ACP validation README not found: $readme" >&2; exit 1; }

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/acp-validation"
cp "$preview_root/turboism-agent.jar" "$bundle_root/turboism-agent.jar"
cp "${mcp_jars[0]}" "$bundle_root/plugins/mcp.jar"
cp "${acp_jars[0]}" "$bundle_root/plugins/acp.jar"
cp "$probe_jar" "$bundle_root/plugins/acp-host-validation-probe.jar"
cp "$fake_agent_jar" "$bundle_root/acp-validation/acp-fake-agent.jar"
printf 'stepTimeoutSeconds=120\n' > "$bundle_root/acp-validation/agent.properties"
cp "$readme" "$bundle_root/README.md"

(
  cd "$bundle_root"
  sha256sum \
    turboism-agent.jar \
    plugins/mcp.jar \
    plugins/acp.jar \
    plugins/acp-host-validation-probe.jar \
    acp-validation/acp-fake-agent.jar \
    acp-validation/agent.properties \
    README.md > SHA256SUMS.txt
)

printf '[package] Windows ACP validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
