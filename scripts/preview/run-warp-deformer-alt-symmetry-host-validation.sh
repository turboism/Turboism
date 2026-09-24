#!/usr/bin/env bash
# Exact-host reconnaissance adapter for Warp deformer Alt symmetry.
#
# Runs the task-local SDK probe on a reviewed Cubism host (5203/5302/5303): it reads every Warp transform
# grid of the reviewed mirror-test fixture, proves the SDK grid
# write/hook/native-Undo round-trip, and — only when the caller supplies an
# explicit screen point — records what a real Robot Alt-drag does to the grid.
#
# Usage:
#   run-warp-deformer-alt-symmetry-host-validation.sh [5303] [run-label] [runner-options...]
#
# Drive mode (opt-in; the screen point must be calibrated against the captured
# before/after screenshots from a previous run):
#   run-warp-deformer-alt-symmetry-host-validation.sh 5303 r1 \
#     --jvm-option -Dturboism.validation.warpAlt.mode=drive \
#     --jvm-option -Dturboism.validation.warpAlt.screen=820,520
# Observe mode (human-in-the-loop; the operator drags control points in the task
# window while the probe records the native ingress hook traffic):
#   run-warp-deformer-alt-symmetry-host-validation.sh 5303 r1 \
#     --jvm-option -Dturboism.validation.warpAlt.mode=observe
set -euo pipefail

# Machine-specific fixture and host paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

version="5303"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  version="$1"
  shift
fi
case "$version" in
  5203|5302|5303) ;;
  *)
    echo "error: warp-deformer Alt-symmetry host validation supports only 5203, 5302 and 5303" >&2
    exit 2
    ;;
esac
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

# Observe mode keeps the host open for a human interaction window, so the runner
# needs a result timeout that covers the ready phase plus the full window.
result_timeout=900
for arg in "$@"; do
  case "$arg" in
    *warpAlt.mode=observe*) result_timeout=2400 ;;
  esac
done

turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
probe_jar="$repo_root/build/warp-deformer-alt-symmetry-host-validation-probe.jar"
plugin_jar="$(find "$repo_root/build/worktree/$worktree_id/warp-deformer-alt-symmetry/libs/" -name 'warp-deformer-alt-symmetry-*.jar' 2>/dev/null | head -1)"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

if [ ! -f "$agent_jar" ]; then
  echo "error: agent jar not found at $agent_jar; run previewBundle first" >&2
  exit 1
fi
if [ ! -f "$probe_jar" ]; then
  echo "error: probe jar not found at $probe_jar; run buildWarpAltSymmetryHostProbe first" >&2
  exit 1
fi
if [ -z "$plugin_jar" ] || [ ! -f "$plugin_jar" ]; then
  echo "error: production plugin jar not found under build/worktree/$worktree_id/warp-deformer-alt-symmetry/libs/; build :plugins:warp-deformer-alt-symmetry:jar first" >&2
  exit 1
fi

exec bash "$runner" \
  --name warp-deformer-alt-symmetry \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$repo_root/build/preview/$worktree_id" \
  --agent "$agent_jar" \
  --plugin "$probe_jar:warp-deformer-alt-symmetry-host-validation-probe.jar" \
  --plugin "$plugin_jar:warp-deformer-alt-symmetry.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --ready-marker 'WARP_ALT_PROBE_READY' \
  --ready-marker 'Turboism Developer Preview started' \
  --result-file 'state/warp-deformer-alt-symmetry-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'WARP_ALT_PROBE_RESULT status=FAIL' \
  --failure-marker 'WARP_ALT_RESULT_WRITE_FAILED' \
  --ready-timeout 300 \
  --result-timeout "$result_timeout" \
  --exit-timeout 120 \
  "$@"
