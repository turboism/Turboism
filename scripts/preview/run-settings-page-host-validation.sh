#!/usr/bin/env bash
# Thin wrapper for the settings-page host probe on exact 5.3.03: the probe drives the real
# Turboism Settings dialog (Performance tab, mesh-triangulation toggle) and reports through a
# task-scoped result file. The unified Runner remains the only lifecycle owner.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-settings-page-host-validation.sh 5303 [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
case "$version" in
  5303) ;;
  *)
    echo "error: settings-page host validation supports only 5303" >&2
    exit 2
    ;;
esac
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture 5303 || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
probe_jar="$repo_root/build/settings-page-host-probe.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

if [ ! -f "$agent_jar" ]; then
  echo "error: agent jar not found at $agent_jar; run previewBundle first" >&2
  exit 1
fi
if [ ! -f "$probe_jar" ]; then
  echo "error: probe jar not found at $probe_jar; run validation/settings-page-probe/build.sh first" >&2
  exit 1
fi

exec bash "$runner" \
  --name settings-page \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$repo_root/build/preview/$worktree_id" \
  --agent "$agent_jar" \
  --plugin "$probe_jar:settings-page-host-probe.jar" \
  --fixture-local "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --result-file 'state/dev.turboism.validation.settingspage/settings-result.txt' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --result-timeout 900 \
  --exit-timeout 180 \
  "$@"
