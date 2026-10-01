#!/usr/bin/env bash
# Test-only 025 read projection smoke, through the shared local queue.
set -euo pipefail
version=5302
run_label='025-us1'
if [[ $# -gt 0 && "$1" != --* ]]; then
  version="$1"
  shift
  if [[ $# -gt 0 && "$1" != --* ]]; then run_label="$1"; shift; fi
fi
case "$version" in
  5000|5001|5002|5003|5004|5005|5006|5100|5101|5102|5103|5200|5201|5202|5203|5300|5301|5302|5303|5304) ;;
  *) echo "error: unreviewed exact version: $version" >&2; exit 2 ;;
esac
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$root/scripts/preview/host-validation-env.sh"
turboism_select_fixture "$version"
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
