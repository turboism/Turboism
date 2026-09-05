#!/usr/bin/env bash
# Narrow feature wrapper; all host identity, isolation and cleanup belong to the generic runner.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
source "$repo_root/scripts/preview/host-validation-env.sh"
run_label=r1
if [[ $# -gt 0 && "$1" != --* ]]; then run_label="$1"; shift; fi
turboism_select_fixture 5302
worktree_id="$(bash "$repo_root/scripts/dev/worktree-id.sh")"
bundle="$repo_root/build/preview/$worktree_id"
exec bash "$repo_root/scripts/preview/run-cubism-host-validation.sh" \
  --name image-archive-reuse \
  --version 5302 \
  --run-label "$run_label" \
  --bundle-root "$bundle" \
  --agent "$bundle/turboism-agent.jar" \
  --home-config "$repo_root/testing/host-validation/image-archive/config.json" \
  --aux-agent "$repo_root/build/image-archive-host-validation-exerciser.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --ready-marker 'Turboism Developer Preview started' \
  --ready-marker 'TURBOISM_IMAGE_ARCHIVE_REUSE installation=COMPLETE' \
  --failure-marker 'TURBOISM_IMAGE_ARCHIVE_REUSE installation=FAILED' \
  --trigger 'state/image-archive/start.flag' \
  --result-file 'state/image-archive/result.properties' \
  --jvm-option '-Dturboism.optimization.imageArchiveReuse=true' \
  --jvm-option '-Dturboism.validation.imageArchive.home={HOME}' \
  --jvm-option '-Dturboism.validation.imageArchive.fixtureName={FIXTURE_NAME}' \
  --ready-timeout 180 \
  --result-timeout 180 \
  --exit-timeout 60 \
  "$@"
