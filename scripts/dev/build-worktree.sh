#!/usr/bin/env bash
# Worktree-isolated build wrapper around Gradle.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "$WT_ROOT"

WT_ID="$(bash "$SCRIPT_DIR/worktree-id.sh")"
export TURBOISM_WORKTREE_ID="$WT_ID"

echo "[build] worktree: $WT_ID"
./gradlew build -PturboismWorktreeId="$WT_ID"

echo "[build] done: $WT_ID"
echo "[build] outputs: $WT_ROOT/build/worktree/$WT_ID"
