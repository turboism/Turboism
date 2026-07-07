#!/usr/bin/env bash
# Worktree-isolated test runner.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "$WT_ROOT"

WT_ID="$(bash "$SCRIPT_DIR/worktree-id.sh")"
export TURBOISM_WORKTREE_ID="$WT_ID"

echo "[test] worktree: $WT_ID"
./gradlew test -PturboismWorktreeId="$WT_ID"

bash scripts/test/test_worktree_id_resolution.sh
bash scripts/test/test_worktree_artifact_boundary.sh

echo "[test] done: $WT_ID"
