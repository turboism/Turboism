#!/usr/bin/env bash
# Thin wrapper for the host dialog automation validation (Lane C, exact-host only).
# Usage: bash scripts/preview/run-dialog-automation-host-validation.sh <5302|5203> [run-label] [runner-options...]
#
# Required runner options (passed through): --fixture-remote <host path> --fixture-sha256 <64-hex>
# Optional: --keep-prefix, --dry-run, --jvm-option, ...
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: run-dialog-automation-host-validation.sh <5302|5203> [run-label] [runner-options...]" >&2
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
  5302|5203) ;;
  *)
    echo "error: version must be 5302 or 5203" >&2
    exit 2
    ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-dialog-automation-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

exec bash "$runner" \
  --name dialog-automation \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$bundle_root/plugins/validation-probe.jar:validation-probe.jar" \
  --jvm-option "-Dturboism.validation.hostVersion=$version" \
  --ready-marker 'DIALOG_AUTO_PROBE_READY' \
  --failure-marker 'DIALOG_AUTO_RESULT status=FAIL' \
  --result-file 'state/dialog-automation-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --ready-timeout 300 \
  --result-timeout 300 \
  --exit-timeout 120 \
  "$@"
