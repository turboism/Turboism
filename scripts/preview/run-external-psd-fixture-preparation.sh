#!/usr/bin/env bash
# Test-only 025 official PSD new-model fixture preparation.
#
# This wrapper deliberately stages only the SDK/runtime agent and the validation probe.  The
# probe's future prepare-fixture phase drives the reviewed official chooser and then performs the
# fixed-grant SAVE_AS; no production product plugin is part of this run.
set -euo pipefail

profile=normal
runner_args=()
while (($# > 0)); do
  case "$1" in
    --profile)
      [ "$#" -ge 2 ] || { echo 'official PSD preparation: --profile requires normal or legacy' >&2; exit 2; }
      profile="$2"
      shift 2
      ;;
    --profile=*)
      profile="${1#--profile=}"
      shift
      ;;
    *)
      runner_args+=("$1")
      shift
      ;;
  esac
done

case "$profile" in
  normal) saved_copy_basename=prepared-control.cmo3 ;;
  legacy) saved_copy_basename=prepared-control-legacy.cmo3 ;;
  *)
    echo "official PSD preparation: unknown profile: $profile" >&2
    exit 2
    ;;
esac

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export TURBOISM_ENV_FILE="${TURBOISM_ENV_FILE:-/opt/dev/projects/turboism/.env}"
# shellcheck source=host-validation-env.sh
source "$root/scripts/preview/host-validation-env.sh"

id="$(bash "$root/scripts/dev/worktree-id.sh")"
fixture="$root/build/host-validation/025-negative-source/queue-81a9c6c0640c4e39a4012fe5dd486b44/native-seven-layer.psd"
fixture_sha256="8b760eb0b6ac5839271210aa0efc681f6a02a6537c1ab97d40a3a56879d8f02c"
target_rgb_sha256="12eca5a1c8d8b9096384c974d52e8f0310c4ad9ac4ea87a072684096cf2b808d"

[ -f "$fixture" ] || {
  echo "official PSD preparation: reviewed derived fixture is missing: $fixture" >&2
  exit 2
}
[ "$(sha256sum -- "$fixture" | cut -d' ' -f1)" = "$fixture_sha256" ] || {
  echo "official PSD preparation: reviewed derived fixture SHA-256 mismatch" >&2
  exit 2
}

exec bash "$root/scripts/preview/run-cubism-host-validation.sh" \
  --name external-psd-fixture-preparation --version 5302 --run-label 025-t021 \
  --bundle-root "$root/build/preview/$id" \
  --agent "$root/build/preview/$id/turboism-agent.jar" \
  --plugin "$root/build/external-psd-edit-host-probe.jar:external-psd-edit-host-probe.jar" \
  --fixture-host "$fixture" --fixture-sha256 "$fixture_sha256" \
  --fixture-name native-seven-layer.psd --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.externalpsd.phase=prepare-fixture' \
  --jvm-option '-Dturboism.validation.externalpsd.runId={TASK_ID}' \
  --jvm-option '-Dturboism.validation.externalpsd.prepare.fixture={FIXTURE}' \
  --jvm-option "-Dturboism.validation.externalpsd.prepare.fixtureSha256=$fixture_sha256" \
  --jvm-option '-Dturboism.validation.externalpsd.prepare.fixtureName={FIXTURE_NAME}' \
  --jvm-option '-Dturboism.validation.externalpsd.prepare.runId={TASK_ID}' \
  --jvm-option '-Dturboism.validation.externalpsd.prepare.taskId={TASK_ID}' \
  --jvm-option "-Dturboism.validation.externalpsd.prepare.profile=$profile" \
  --jvm-option "-Dturboism.validation.externalpsd.prepare.savedCopy={HOME}/$saved_copy_basename" \
  --jvm-option "-Dturboism.validation.externalpsd.prepare.targetRgbSha256=$target_rgb_sha256" \
  --jvm-option '-Dturboism.validation.externalpsd.prepare.hostVersion=5.3.02' \
  --jvm-option '-Dturboism.validation.externalpsd.prepare.timeoutMillis=180000' \
  --jvm-option "-Dturboism.preview.userFileFixedGrant={HOME}/$saved_copy_basename" \
  --failure-marker 'EXTERNAL_PSD_EDIT_RESULT status=BLOCKED' \
  --result-file state/dev.turboism.validation.externalpsd/external-psd-edit-result.properties \
  --result-pass-line 'status=PASS' --result-fail-line 'status=FAIL' \
  --result-timeout 480 --exit-timeout 90 "${runner_args[@]}"
