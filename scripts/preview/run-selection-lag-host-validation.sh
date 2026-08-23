#!/usr/bin/env bash
# Selection-lag diagnostic adapter for the generic exact-host runner.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-selection-lag-host-validation.sh <5203|5302> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/selection-lag-validation"
probe_jar="$repo_root/build/selection-lag-probe.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

# The generic runner stages a purpose-named project copy inside an independent
# CoW prefix, so unrelated exact-host sessions can stay open concurrently.
exec bash "$runner" \
  --name selection-lag \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$bundle_root/plugins/parameter.jar:parameter.jar" \
  --plugin "$bundle_root/plugins/context-menu.jar:context-menu.jar" \
  --plugin "$probe_jar:selection-lag-probe.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.exitOnComplete=true' \
  --jvm-option "-Dturboism.validation.hostVersion=$version" \
  --jvm-option '-Dturboism.validation.runId={TASK_ID}' \
  --jvm-option '-Dturboism.validation.thresholdMs=100' \
  --ready-marker 'SELECTION_LAG_PROBE_READY' \
  --ready-marker 'Plugin load complete' \
  --trigger 'state/dev.turboism.validation.selection-lag/exerciser.flag' \
  --result-file 'state/selection-lag-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'SELECTION_LAG_PROBE_RESULT status=FAIL' \
  --failure-marker 'SELECTION_LAG_PROBE_RESULT status=BLOCKED' \
  --failure-marker 'SELECTION_LAG_PROBE_FLAG_TIMEOUT' \
  --ready-timeout 300 \
  --result-timeout 900 \
  --exit-timeout 120 \
  "$@"
