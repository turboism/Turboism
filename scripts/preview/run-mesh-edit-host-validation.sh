#!/usr/bin/env bash
# Exact-host wrapper for the direct mesh edit validation probe.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-mesh-edit-host-validation.sh <matrix|persistence> [run-label] [runner-options...]" >&2
  exit 2
fi
mode="$1"
shift
case "$mode" in
  matrix|persistence) ;;
  *) echo "error: unsupported mode: $mode" >&2; exit 2 ;;
esac
run_label='r1'
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle="$repo_root/build/manual-test/$worktree_id/mesh-edit-validation"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
turboism_select_fixture 5203 || exit 2
fixture="$fixture_src"

[ -f "$bundle/turboism-agent.jar" ] || { echo "bundle missing: $bundle" >&2; exit 1; }

exec bash "$runner" \
  --name mesh-edit \
  --version 5203 \
  --run-label "$run_label-$mode" \
  --bundle-root "$bundle" \
  --agent "$bundle/turboism-agent.jar" \
  --home-config "$bundle/home-config.json" \
  --plugin "$bundle/plugins/mesh-edit-mirror-axis-enhance.jar:mesh-edit-mirror-axis-enhance.jar" \
  --plugin "$bundle/plugins/mesh-edit-validation-probe.jar:mesh-edit-validation-probe.jar" \
  --fixture-remote "$fixture" \
  --fixture-sha256 "$fixture_sha256" \
  --jvm-option "-Dturboism.meshEditValidation.mode=$mode" \
  --jvm-option '-Dturboism.validation.fixture={FIXTURE}' \
  --jvm-option '-Dturboism.validation.exitOnComplete=true' \
  --ready-marker 'Windows mesh edit validation probe initialized' \
  --ready-marker 'Plugin load complete' \
  --result-file 'state/dev.turboism.validation.mesh-edit/mesh-edit-host-validation.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'MESH_EDIT_HOST_VALIDATION_RESULT status=FAIL' \
  --ready-timeout 300 \
  --result-timeout 900 \
  --exit-timeout 120 \
  "$@"
