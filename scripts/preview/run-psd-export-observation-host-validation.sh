#!/usr/bin/env bash
# Native export observation only: not PSD fidelity/replace/external-edit acceptance.
set -euo pipefail
version=5302
run_label='025-us2-observation'
if [[ $# -gt 0 && "$1" != --* ]]; then
  version="$1"
  shift
  if [[ $# -gt 0 && "$1" != --* ]]; then run_label="$1"; shift; fi
fi
case "$version" in
  5300|5301|5302|5303|5304) ;;
  *) echo "error: unreviewed exact version: $version" >&2; exit 2 ;;
esac
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$root/scripts/preview/host-validation-env.sh"
turboism_select_fixture "$version"
id="$(bash "$root/scripts/dev/worktree-id.sh")"
exec bash "$root/scripts/preview/run-cubism-host-validation.sh" \
  --name psd-export-observation --version "$version" --run-label "$run_label" \
  --bundle-root "$root/build/preview/$id" \
  --agent "$root/build/preview/$id/turboism-agent.jar" \
  --plugin "$root/build/psd-export-observation-host-probe.jar:psd-export-observation-host-probe.jar" \
  --fixture-host "$fixture_src" --fixture-sha256 "$fixture_sha256" \
  --fixture-name psd-export-observation-025.cmo3 --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.textures.runId={TASK_ID}' \
  --jvm-option "-Dturboism.validation.textures.expectedVersion=$version" \
  --jvm-option '-Dturboism.validation.textures.exportObservation=true' \
  --result-file state/dev.turboism.validation.textures/relations-result.properties \
  --result-pass-line status=PASS --result-fail-line status=FAIL \
  --result-timeout 360 --exit-timeout 60 "$@"
