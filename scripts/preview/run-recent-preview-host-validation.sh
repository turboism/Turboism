#!/usr/bin/env bash
# Recent-preview adapter for the generic exact-host runner.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-recent-preview-host-validation.sh $(turboism_task_versions_usage recent-preview) [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
turboism_require_task_version recent-preview "$version" || exit 2
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-recent-preview-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
if [ ! -f "$runner" ]; then
  runner="${TURBOISM_HOST_VALIDATION_RUNNER:-}"
fi
if [ -z "$runner" ] || [ ! -f "$runner" ]; then
  echo "error: generic host runner not found in this worktree and TURBOISM_HOST_VALIDATION_RUNNER is missing or invalid" >&2
  exit 2
fi

# The probe saves a task-private working copy under turboism-home/state, never
# the delivered fixture copy, so the runner's unconditional copied-fixture
# invariant holds; --require-fixture-unchanged needs no separate opt-in.
exec bash "$runner" \
  --name recent-preview \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$bundle_root/plugins/recent-preview.jar:recent-preview.jar" \
  --plugin "$bundle_root/plugins/recent-preview-validation-probe.jar:recent-preview-validation-probe.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --jvm-option "-Dturboism.validation.hostVersion=$version" \
  --jvm-option '-Dturboism.validation.runId={TASK_ID}' \
  --jvm-option '-Dturboism.validation.exitOnComplete=true' \
  --ready-marker 'Windows recent preview validation probe initialized' \
  --ready-marker 'Plugin load complete' \
  --failure-marker 'RECENT_PREVIEW_HOST_RESULT status=FAIL' \
  --result-file 'state/dev.turboism.validation.recent-preview/result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --ready-timeout 300 \
  --result-timeout 900 \
  --exit-timeout 120 \
  "$@"
