#!/usr/bin/env bash
set -euo pipefail
repo="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo"
source scripts/preview/host-validation-env.sh
[ "$#" -ge 1 ] || { echo "usage: run-undo-host-validation.sh <version-code> [runner-options...]" >&2; exit 2; }
version="$1"
shift
turboism_select_fixture "$version"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo/scripts/dev/worktree-id.sh")"
exec bash scripts/preview/run-cubism-host-validation.sh \
 --name incremental-update --version "$version" --run-label undo-native-r1 \
 --bundle-root "$repo/build/preview/$worktree_id" \
 --agent "$repo/build/preview/$worktree_id/turboism-agent.jar" \
 --home-config "$repo/validation/incremental-update-host-probe/undo-config.json" \
 --aux-agent "$repo/build/incremental-undo-host-agent.jar:incremental-undo-host-agent.jar" \
 --fixture-host "$fixture_src" --fixture-sha256 "$fixture_sha256" \
 --fixture-name incremental-undo.cmo3 --require-fixture-unchanged \
 --ready-marker 'TURBOISM_INCREMENTAL_UPDATE installation=COMPLETE' \
 --result-file state/incremental-undo/result.txt --result-pass-line status=PASS --result-fail-line status=FAIL \
 --jvm-option '-Dturboism.optimization.incrementalUpdate=true' \
 --jvm-option '-Dturboism.optimization.modelUpdateSkip=false' \
 --jvm-option '-Dturboism.validation.runId={TASK_ID}' \
 --ready-timeout 300 --result-timeout 420 --exit-timeout 30 "$@"
