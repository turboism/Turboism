#!/usr/bin/env bash
# Theme-specific adapter for the generic exact-host runner.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-theme-host-validation.sh <5000|5001|5002|5003|5004|5005|5006|5100|5101|5102|5103|5200|5201|5202|5203|5300|5301|5302|5303|5304> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
case "$version" in
  5000|5001|5002|5003|5004|5005|5006|5100|5101|5102|5103|5200|5201|5202|5203|5300|5301|5302|5303|5304) ;;
  *)
    echo "error: unsupported version: $version" >&2
    exit 2
    ;;
esac
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/windows-theme-validation"
probe_jar="$repo_root/build/theme-host-validation-exerciser.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

exec bash "$runner" \
  --name theme \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$bundle_root/plugins/ui-theme.jar:ui-theme.jar" \
  --plugin "$probe_jar:theme-host-validation-exerciser.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --ready-marker 'THEME_EXERCISER_READY' \
  --ready-marker 'Plugin load complete' \
  --ready-marker 'UiThemePlugin enabled' \
  --trigger 'state/dev.turboism.validation.theme/exerciser.flag' \
  --result-marker 'THEME_MATRIX_RESULT status=PASS' \
  --failure-marker 'THEME_MATRIX_RESULT status=FAIL' \
  --failure-marker 'THEME_EXERCISER_FLAG_TIMEOUT' \
  --ready-timeout 240 \
  --result-timeout 180 \
  --exit-timeout 120 \
  "$@"
