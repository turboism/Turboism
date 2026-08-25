#!/usr/bin/env bash
# Archives exact-host Turboism logs and non-secret state for validation evidence.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  printf 'usage: archive-cubism-host-evidence.sh <turboism-home> <evidence-dir>\n' >&2
  exit 2
fi

home="$1"
evidence="$2"
entries=()

[ -d "$home/logs" ] && entries+=(logs)
[ -d "$home/state" ] && entries+=(state)
[ "${#entries[@]}" -gt 0 ] || exit 0

mkdir -p "$evidence"
tar -C "$home" -cf "$evidence/turboism-home-logs-state.tar" \
  --exclude='state/dev.turboism.plugin.mcp/mcp-connection.json' \
  --exclude='state/dev.turboism.plugin.mcp/.mcp-connection-*.tmp' \
  --exclude='state/dev.turboism.plugin.mcp/mcp-connection-*.tmp' \
  "${entries[@]}"
