#!/usr/bin/env bash
# Core acquisition probe adapter for the generic exact-host runner.
set -euo pipefail

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

case "$version" in
  5302)
    fixture_src='<local-home>/Documents/测试 混合模式.cmo3'
    fixture_sha256='57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4'
    profile='cubism-5.3.02'
    editor_jar_sha256='988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21'
    core_jar_sha256='98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4'
    ;;
  5203)
    fixture_src='<local-home>/TurboismPartValidation/part52-official/part-opacity-fixture-52-final.cmo3'
    fixture_sha256='331bbb4cbdb1287f5bd063a0661d94c2860534baa7d0f76bb055ed070a21b028'
    profile='cubism-5.2'
    editor_jar_sha256='bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd'
    core_jar_sha256='85959a0572be02ee45d128cfdaf9046631241310b741d6b149d295a0dec7451e'
    ;;
  *)
    echo "error: version must be 5302 or 5203" >&2
    exit 2
    ;;
esac

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
