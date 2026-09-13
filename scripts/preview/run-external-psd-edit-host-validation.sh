#!/usr/bin/env bash
# Test-only 025 external PSD edit pipeline probe, through the shared local queue.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$root/scripts/preview/host-validation-env.sh"
turboism_select_fixture 5302
id="$(bash "$root/scripts/dev/worktree-id.sh")"
exec bash "$root/scripts/preview/run-cubism-host-validation.sh" \
  --name external-psd-edit-pipeline --version 5302 --run-label 025-us4 \
  --bundle-root "$root/build/preview/$id" \
  --agent "$root/build/preview/$id/turboism-agent.jar" \
  --plugin "$root/build/external-psd-edit-host-probe.jar:external-psd-edit-host-probe.jar" \
  --fixture-host "$fixture_src" --fixture-sha256 "$fixture_sha256" \
  --fixture-name external-psd-edit-025.cmo3 --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.externalpsd.runId={TASK_ID}' \
  --result-file state/dev.turboism.validation.externalpsd/external-psd-edit-result.properties \
  --result-pass-line status=PASS --result-fail-line status=FAIL \
  --result-timeout 480 --exit-timeout 90 "$@"
