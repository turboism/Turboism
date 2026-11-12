#!/usr/bin/env bash
# Deformer batch-rename-specific adapter for the generic exact-host runner.
#
# Measures the three costs the batch-rename plugin depends on, on the exact host:
# the naming-index read, per-deformer renames, and one-edit batch renames.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-deformer-rename-host-validation.sh $(turboism_task_versions_usage deformer-rename) [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
turboism_require_task_version deformer-rename "$version" || exit 2
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi
turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-deformer-rename-validation"
probe_jar="$repo_root/build/deformer-rename-validation-exerciser.jar"
plugin_jar="$(ls -t "$repo_root"/build/worktree/"$worktree_id"/deformer-batch-rename/libs/deformer-batch-rename-*.jar | head -1)"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

exec bash "$runner" \
  --name deformer-rename \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$plugin_jar:deformer-batch-rename.jar" \
  --plugin "$probe_jar:deformer-rename-validation-exerciser.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.exitOnComplete=true' \
  --jvm-option "-Dturboism.validation.hostVersion=$version" \
  --jvm-option '-Dturboism.validation.runId={TASK_ID}' \
  --jvm-option '-Dturboism.validation.fixturePath={FIXTURE}' \
  --ready-marker 'DEFORMER_RENAME_PROBE_READY' \
  --ready-marker '[dev.turboism.plugin.deformer-batch-rename] Plugin lifecycle: load succeeded' \
  --ready-marker 'Plugin load complete' \
  --trigger 'state/dev.turboism.validation.deformer-rename/exerciser.flag' \
  --result-file 'state/deformer-rename-validation-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --result-fail-line 'status=BLOCKED' \
  --result-fail-line 'status=SKIPPED' \
  --require-assertions \
  --failure-marker 'DEFORMER_RENAME_PROBE_RESULT status=FAIL' \
  --failure-marker 'DEFORMER_RENAME_PROBE_RESULT status=BLOCKED' \
  --failure-marker 'DEFORMER_RENAME_PROBE_RESULT status=SKIPPED' \
  "$@"
