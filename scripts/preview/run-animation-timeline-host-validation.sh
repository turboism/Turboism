#!/usr/bin/env bash
# Animation-timeline adapter for the generic exact-host runner.
#
# Matrix per version:
#   fixture — the exerciser builds a CAnimationFileContent in the running
#             editor through native host constructors (project child, two
#             scenes, one Live2D model track linked to the active model source,
#             a parameter effect with two keyed attributes).
#   read    — model.animationDocuments() enumeration + timeline projection.
#   scene   — playhead/seekTo, activate, rename, default curve type.
#   attr    — setKeyframe/removeKeyframe/offset/scale/quantize/applyCurveType
#             with native undo-manager depth evidence and undo/redo restore.
#   eval    — recordKeyframe and bakeEvaluated through the live scene instance.
# The runner records terminal PASS/FAIL from the result properties file.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-animation-timeline-host-validation.sh <5303|5302|5203> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

case "$version" in
  5303|5302|5203)
    turboism_select_fixture "$version" || exit 2
    ;;
  *)
    echo "error: version must be 5303, 5302, or 5203" >&2
    exit 2
    ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-animation-timeline-validation"
probe_jar="$repo_root/build/animation-timeline-host-validation-probe.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

exec bash "$runner" \
  --name animation-timeline \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$probe_jar:animation-timeline-host-validation-probe.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --jvm-option '-Dturboism.validation.hostVersion='$version \
  --jvm-option '-Dturboism.validation.runId={TASK_ID}' \
  --ready-marker 'ANIM_PROBE_READY' \
  --trigger 'state/dev.turboism.validation.animation-timeline/exerciser.flag' \
  --result-file 'state/animation-timeline-validation-result.properties' \
  --result-pass-line 'terminal=PASS' \
  --result-fail-line 'terminal=FAIL' \
  --failure-marker 'ANIM_MATRIX_RESULT status=FAIL' \
  --failure-marker 'ANIM_PROBE_FLAG_TIMEOUT' \
  --ready-timeout 300 \
  --result-timeout 900 \
  --exit-timeout 120 \
  "$@"
