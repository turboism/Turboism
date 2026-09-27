#!/usr/bin/env bash
# Edit-session adapter for the generic exact-host runner.
#
# Matrix per version (spec 046 T7):
#   matrix    — all steps S1..S6
#   lock      — S1 session lock (non-silent + silent reveal)
#   cancel    — S2 cancellation restoration + S3 commit semantics
#   semantics — S4 semantic edge cases + S6 fail-closed behavior
#   getobject — S5 GetObject read matrix
# The runner records terminal PASS/FAIL from the result properties file.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-edit-host-validation.sh <5203|5302|5303> [mode|run-label] [mode|run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
mode='matrix'
run_label='r1'
# Positional args may arrive in either order (the task catalog passes
# "{version} {runLabel}" only): a value matching the mode set selects the
# probe mode, anything else is the run label.
for _ in 1 2; do
  if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
    case "$1" in
      matrix|lock|cancel|semantics|getobject) mode="$1" ;;
      *) run_label="$1" ;;
    esac
    shift
  fi
done

turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-edit-validation"
probe_jar="$repo_root/build/edit-host-validation-probe.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

exec bash "$runner" \
  --name edit \
  --version "$version" \
  --run-label "$run_label-$mode" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$probe_jar:edit-host-validation-probe.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option "-Dturboism.edit.validation.mode=$mode" \
  --jvm-option '-Dturboism.validation.hostVersion='$version \
  --jvm-option '-Dturboism.validation.runId={TASK_ID}' \
  --ready-marker 'Plugin load complete' \
  --ready-marker 'EDIT_PROBE_READY' \
  --trigger 'state/dev.turboism.validation.edit-host/exerciser.flag' \
  --result-file 'state/edit-host-validation-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'EDIT_HOST_PROBE_RESULT status=FAIL' \
  --failure-marker 'EDIT_PROBE_FLAG_TIMEOUT' \
  --ready-timeout 300 \
  --result-timeout 900 \
  --exit-timeout 120 \
  "$@"
