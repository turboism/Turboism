#!/usr/bin/env bash
# Core acquisition probe adapter for the generic exact-host runner.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-core-acquisition-host-validation.sh <5302|5203> [run-label] [runner-options...]" >&2
  exit 2
fi

version="$1"
shift
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="${TURBOISM_WORKTREE_ID:-$(bash "$repo_root/scripts/dev/worktree-id.sh")}"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-core-acquisition-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

exec bash "$runner" \
  --name core-acquisition \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --aux-agent "$bundle_root/core-acquisition-probe-agent.jar:core-acquisition-probe-agent.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option "-Dturboism.coreAcquisition.profile=$profile" \
  --jvm-option "-Dturboism.coreAcquisition.expectedEditorSha256=$editor_jar_sha256" \
  --jvm-option "-Dturboism.coreAcquisition.expectedCoreSha256=$core_jar_sha256" \
  --jvm-option '-Dturboism.coreAcquisition.exitOnComplete=true' \
  --result-file 'state/core-acquisition-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --ready-timeout 300 \
  --result-timeout 480 \
  --exit-timeout 120 \
  "$@"
