#!/usr/bin/env bash
# Native export observation only: not PSD fidelity/replace/external-edit acceptance.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$root/scripts/preview/host-validation-env.sh"
turboism_select_fixture 5302
id="$(bash "$root/scripts/dev/worktree-id.sh")"
exec bash "$root/scripts/preview/run-cubism-host-validation.sh" \
  --name psd-export-observation --version 5302 --run-label 025-us2-observation \
  --bundle-root "$root/build/preview/$id" \
  --agent "$root/build/preview/$id/turboism-agent.jar" \
  --plugin "$root/build/psd-export-observation-host-probe.jar:psd-export-observation-host-probe.jar" \
  --fixture-host "$fixture_src" --fixture-sha256 "$fixture_sha256" \
  --fixture-name psd-export-observation-025.cmo3 --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.textures.runId={TASK_ID}' \
  --jvm-option '-Dturboism.validation.textures.exportObservation=true' \
  --result-file state/dev.turboism.validation.textures/relations-result.properties \
  --result-pass-line status=PASS --result-fail-line status=FAIL \
  --result-timeout 360 --exit-timeout 60 "$@"
