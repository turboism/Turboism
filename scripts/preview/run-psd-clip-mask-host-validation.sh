#!/usr/bin/env bash
# PSD clip-mask import adapter for the generic exact-host runner.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-psd-clip-mask-host-validation.sh <5203|5302> [mode] [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
mode='matrix'
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  mode="$1"
  shift
fi
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

case "$mode" in
  read|matrix) ;;
  *)
    echo "error: unsupported validation mode: $mode" >&2
    exit 2
    ;;
esac

case "$version" in
  5302)
    turboism_require_env TURBOISM_HOST_VALIDATION_FIXTURE_PSD "PSD fixture path" || exit 2
    fixture_src="$TURBOISM_HOST_VALIDATION_FIXTURE_PSD"
    fixture_sha256="$TURBOISM_HOST_VALIDATION_FIXTURE_PSD_SHA256"
    fixture_name="${TURBOISM_HOST_VALIDATION_FIXTURE_PSD_NAME:-clipmask.psd}"
    ;;
  5203)
    # Both exact hosts validate against the same small real PSD fixture
    # (clipmask.psd, 169008 bytes, independently confirmed host identity);
    # Local overrides come from the documented TURBOISM_HOST_VALIDATION_FIXTURE_PSD variables.
    turboism_require_env TURBOISM_HOST_VALIDATION_FIXTURE_PSD "PSD fixture path" || exit 2
    fixture_src="$TURBOISM_HOST_VALIDATION_FIXTURE_PSD"
    fixture_sha256="$TURBOISM_HOST_VALIDATION_FIXTURE_PSD_SHA256"
    fixture_name="${TURBOISM_HOST_VALIDATION_FIXTURE_PSD_NAME:-clipmask.psd}"
    ;;
  *)
    echo "error: version must be 5203 or 5302" >&2
    exit 2
    ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-psd-clip-mask-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

exec bash "$runner" \
  --name psd-clip-mask \
  --version "$version" \
  --run-label "$run_label-$mode" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$bundle_root/plugins/psd-clip-mask-import.jar:psd-clip-mask-import.jar" \
  --plugin "$bundle_root/plugins/psd-clip-mask-validation-probe.jar:psd-clip-mask-validation-probe.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-name "$fixture_name" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option "-Dturboism.psdClipMaskValidation.mode=$mode" \
  --jvm-option '-Dturboism.validation.exitOnComplete=true' \
  --ready-marker 'Windows PSD clip-mask validation probe initialized' \
  --ready-marker 'Plugin load complete' \
  --result-file 'state/host-validation-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'HOST_VALIDATION_RESULT status=FAIL' \
  --ready-timeout 300 \
  --result-timeout 900 \
  --exit-timeout 120 \
  "$@"
