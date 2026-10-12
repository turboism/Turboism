#!/usr/bin/env bash
# Test-only 025 read projection smoke, through the shared local queue.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$root/scripts/preview/host-validation-env.sh"
version="$TURBOISM_HOST_VALIDATION_DEFAULT_VERSION"
run_label='025-us1'
if [[ $# -gt 0 && "$1" != --* ]]; then
  version="$1"
  shift
  if [[ $# -gt 0 && "$1" != --* ]]; then run_label="$1"; shift; fi
fi
turboism_require_task_version texture-relations "$version" || exit 2
turboism_select_fixture "$version" || exit 2
id="$(bash "$root/scripts/dev/worktree-id.sh")"
exec bash "$root/scripts/preview/run-cubism-host-validation.sh" \
  --name texture-relations-smoke --version "$version" --run-label "$run_label" \
  --bundle-root "$root/build/preview/$id" \
  --agent "$root/build/preview/$id/turboism-agent.jar" \
  --plugin "$root/build/texture-relations-host-probe.jar:texture-relations-host-probe.jar" \
  --fixture-host "$fixture_src" --fixture-sha256 "$fixture_sha256" \
  --fixture-name texture-relations-025.cmo3 --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.textures.runId={TASK_ID}' \
  --jvm-option "-Dturboism.validation.textures.expectedVersion=$version" \
  --result-file state/dev.turboism.validation.textures/relations-result.properties \
  --result-pass-line status=PASS --result-fail-line status=FAIL \
  --result-timeout 240 --exit-timeout 60 "$@"
