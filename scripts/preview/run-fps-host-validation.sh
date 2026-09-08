#!/usr/bin/env bash
# FPS counting adapter for the generic exact-host runner: 5.2.03 (new
# admission) and 5.3.02 (regression). The exerciser plugin subscribes through
# the public SDK performance service; the runtime then mounts the FPS counting
# hook and the exerciser records renderSceneCalls evidence.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-fps-host-validation.sh <5203|5302> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
turboism_select_fixture "$version" || exit 2
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
probe_jar="$repo_root/build/fps-host-validation-exerciser.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

if [ ! -f "$agent_jar" ]; then
  echo "error: agent jar not found at $agent_jar; run previewBundle first" >&2
  exit 1
fi
if [ ! -f "$probe_jar" ]; then
  echo "error: probe jar not found at $probe_jar; run buildFpsHostProbe first" >&2
  exit 1
fi

# The generic Runner owns this local hook's task lifecycle. It snapshots the
# reviewed driver path, starts it only after the task is admitted/launched, and
# contains it alongside Cubism in the admitted scope. {FIXTURE_NAME}
# resolves after QUEUE_RUN_ID is selected, so prepared and executed tasks agree.
fixture_suffix='fps.cmo3'
driver="$repo_root/scripts/preview/fps-resize-driver.sh"
[ -r "$driver" ] || { echo "error: FPS resize hook is missing at $driver" >&2; exit 1; }
exec bash "$runner" \
  --name fps \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$repo_root/build/preview/$worktree_id" \
  --agent "$agent_jar" \
  --plugin "$probe_jar:fps-host-validation-exerciser.jar" \
  --fixture-host "$fixture_src" \
  --fixture-name "$fixture_suffix" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --remote-pre-launch "$driver" \
  --remote-pre-launch-background \
  --remote-pre-launch-args-only \
  --remote-pre-launch-arg '{FIXTURE_NAME}' \
  --ready-marker 'FPS_EXERCISER_READY' \
  --result-file 'state/dev.turboism.validation.fps/result.txt' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --ready-timeout 240 \
  --result-timeout 300 \
  --exit-timeout 120 \
  "$@"
