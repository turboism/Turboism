#!/usr/bin/env bash
# Exact-host validation adapter for the BoundingBox overlay Warp mirror plugin.
#
# Runs the task-local probe on Cubism 5.2.03/5.3.02/5.3.03 (fixture permitting): it applies all four mirror
# directions through the public warpMirror() service on the reviewed mirror-test
# fixture, verifies direction labels against the committed grid, descendant canvas
# preservation, the atomic native Undo/Redo pair, NO_CHANGE, and fail-closed
# rejection — while the production plugin and its overlay contribution stay loaded.
# The authorized warp-deformer-alt-symmetry consumer plugin is also staged so the
# warp-alt-mirror hook binds and the warp-mirror capability stays admitted.
#
# Usage:
#   run-boundingbox-warp-mirror-host-validation.sh [5303] [run-label] [runner-options...]
set -euo pipefail

# Machine-specific fixture and host paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

version="$TURBOISM_HOST_VALIDATION_DEFAULT_FIXTURE_VERSION"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  version="$1"
  shift
fi
turboism_require_task_version boundingbox-warp-mirror "$version" || exit 2
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
probe_jar="$repo_root/build/boundingbox-warp-mirror-host-validation-probe.jar"
plugin_jar="$(find "$repo_root/build/worktree/$worktree_id/boundingbox-warp-mirror/libs/" -name 'boundingbox-warp-mirror-*.jar' 2>/dev/null | head -1)"
# The cubism.editor-model.warp-mirror capability is conditioned on the
# warp-alt-mirror runtime hook, which binds only while its one authorized
# consumer plugin is loaded; without it every warpMirror() call reports
# BLOCKED UNAVAILABLE.
consumer_jar="$(find "$repo_root/build/worktree/$worktree_id/warp-deformer-alt-symmetry/libs/" -name 'warp-deformer-alt-symmetry-*.jar' 2>/dev/null | head -1)"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

if [ ! -f "$agent_jar" ]; then
  echo "error: agent jar not found at $agent_jar; run previewBundle first" >&2
  exit 1
fi
if [ ! -f "$probe_jar" ]; then
  echo "error: probe jar not found at $probe_jar; run buildBoundingBoxWarpMirrorHostProbe first" >&2
  exit 1
fi
if [ -z "$plugin_jar" ] || [ ! -f "$plugin_jar" ]; then
  echo "error: production plugin jar not found under build/worktree/$worktree_id/boundingbox-warp-mirror/libs/; build :plugins:boundingbox-warp-mirror:jar first" >&2
  exit 1
fi
if [ -z "$consumer_jar" ] || [ ! -f "$consumer_jar" ]; then
  echo "error: warp-alt-mirror consumer plugin jar not found under build/worktree/$worktree_id/warp-deformer-alt-symmetry/libs/; build :plugins:warp-deformer-alt-symmetry:jar first" >&2
  exit 1
fi

exec bash "$runner" \
  --name boundingbox-warp-mirror \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$repo_root/build/preview/$worktree_id" \
  --agent "$agent_jar" \
  --plugin "$probe_jar:boundingbox-warp-mirror-host-validation-probe.jar" \
  --plugin "$plugin_jar:boundingbox-warp-mirror.jar" \
  --plugin "$consumer_jar:warp-deformer-alt-symmetry.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --ready-marker 'WARP_MIRROR_PROBE_READY' \
  --ready-marker 'Turboism Developer Preview started' \
  --result-file 'state/boundingbox-warp-mirror-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'WARP_MIRROR_PROBE_RESULT status=FAIL' \
  --failure-marker 'WARP_MIRROR_RESULT_WRITE_FAILED' \
  --ready-timeout 300 \
  --result-timeout 2400 \
  --exit-timeout 120 \
  "$@"
