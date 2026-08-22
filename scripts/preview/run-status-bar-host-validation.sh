#!/usr/bin/env bash
# Status-bar adapter for exact-host validation: 5.2.03 and 5.3.02 both run the visible manager/compact matrix.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-status-bar-host-validation.sh <5203|5302> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
case "$version" in
  5203) mode='manager' ;;
  5302) mode='manager' ;;
  *)
    echo "error: status-bar host validation supports only 5203 or 5302" >&2
    exit 2
    ;;
esac
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture 5302 || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
probe_jar="$repo_root/build/status-bar-host-validation-exerciser.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

if [ ! -f "$agent_jar" ]; then
  echo "error: agent jar not found at $agent_jar; run previewBundle first" >&2
  exit 1
fi
if [ ! -f "$probe_jar" ]; then
  echo "error: probe jar not found at $probe_jar; run buildStatusBarHostProbe first" >&2
  exit 1
fi

exec bash "$runner" \
  --name status-bar \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$repo_root/build/preview/$worktree_id" \
  --agent "$agent_jar" \
  --plugin "$probe_jar:status-bar-host-validation-exerciser.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option "-Dturboism.validation.statusBar.mode=$mode" \
  --result-file 'state/dev.turboism.validation.statusbar/matrix-result.txt' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --result-timeout 300 \
  --exit-timeout 120 \
  "$@"
