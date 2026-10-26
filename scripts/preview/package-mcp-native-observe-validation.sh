#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle="$repo_root/build/manual-test/$worktree_id/windows-mcp-validation"
bash "$repo_root/scripts/preview/package-windows-mcp-validation.sh" "$bundle"
bash "$repo_root/validation/acp-native-host-probe/build.sh" "$bundle/native-observe" "" "${1:-pi}" "${2:-observe}"
( cd "$bundle"; sha256sum native-observe/probe.jar native-observe/runtime.json >> SHA256SUMS.txt )
