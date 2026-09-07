#!/usr/bin/env bash
# Validation-only bounding-box overlay adapter for the generic exact-host runner.
# Proves two public-SDK contributions are visible, stable, independently clickable,
# and detached after their registrations close on exact Cubism 5.2.03/5.3.02/5.3.03.
set -euo pipefail

# Machine-specific fixture and host paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-bounding-box-overlay-host-validation.sh <5203|5302|5303> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
case "$version" in
  5203|5302|5303) ;;
  *)
    echo "error: bounding-box overlay host validation supports only 5203, 5302, or 5303" >&2
    exit 2
    ;;
esac
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
probe_jar="$repo_root/build/bounding-box-overlay-host-validation-exerciser.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

if [ ! -f "$agent_jar" ]; then
  echo "error: agent jar not found at $agent_jar; run previewBundle first" >&2
  exit 1
fi
if [ ! -f "$probe_jar" ]; then
  echo "error: probe jar not found at $probe_jar; run buildBoundingBoxOverlayHostProbe first" >&2
  exit 1
fi

exec bash "$runner" \
  --name bounding-box-overlay \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$repo_root/build/preview/$worktree_id" \
  --agent "$agent_jar" \
  --plugin "$probe_jar:bounding-box-overlay-host-validation-exerciser.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --ready-marker 'BOUNDING_BOX_PROBE_READY' \
  --ready-marker 'Turboism Developer Preview started' \
  --trigger 'state/dev.turboism.validation.boundingbox/exerciser.flag' \
  --result-file 'state/bounding-box-overlay-host-validation-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'BOUNDING_BOX_PROBE_RESULT status=FAIL' \
  --failure-marker 'BOUNDING_BOX_PROBE_FLAG_TIMEOUT' \
  --failure-marker 'BOUNDING_BOX_SCREEN_CAPTURE_FAILED' \
  --ready-timeout 300 \
  --result-timeout 600 \
  --exit-timeout 120 \
  "$@"
