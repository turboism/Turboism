#!/usr/bin/env bash
# FPS counting adapter for the generic exact-host runner: 5.2.03 (new
# admission) and 5.3.02 (regression). The exerciser plugin subscribes through
# the public SDK performance service; the runtime then mounts the FPS counting
# hook and the exerciser records renderSceneCalls evidence.
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: run-fps-host-validation.sh <5203|5302> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
case "$version" in
  5203)
    fixture_src='<local-home>/TurboismPartValidation/part52-official/part-opacity-fixture-52-final.cmo3'
    fixture_sha256='331bbb4cbdb1287f5bd063a0661d94c2860534baa7d0f76bb055ed070a21b028'
    ;;
  5302)
    fixture_src='<local-home>/Documents/测试 混合模式.cmo3'
    fixture_sha256='57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4'
    ;;
  *)
    echo "error: version must be 5203 or 5302" >&2
    exit 2
    ;;
esac
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

# Bounded host-side resize driver: forces modeling-canvas repaints inside the
# exerciser sampling window (see fps-resize-driver.sh). Killed when this
# wrapper (and with it the runner) exits; it never touches other sessions.
driver="$repo_root/scripts/preview/fps-resize-driver.sh"
driver_pid=''
if [ -r "$driver" ]; then
  nohup ssh -i "$HOME/.ssh/<validation-ssh-key>" -o IdentitiesOnly=yes \
    <validation-user>@<validation-host> "bash -s" < "$driver" > /tmp/fps-resize-driver.out 2>&1 &
  driver_pid=$!
fi
trap 'if [ -n "$driver_pid" ]; then kill "$driver_pid" 2>/dev/null || true; fi' EXIT
bash "$runner" \
  --name fps \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$repo_root/build/preview/$worktree_id" \
  --agent "$agent_jar" \
  --plugin "$probe_jar:fps-host-validation-exerciser.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --ready-marker 'FPS_EXERCISER_READY' \
  --result-file 'state/dev.turboism.validation.fps/result.txt' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --ready-timeout 240 \
  --result-timeout 300 \
  --exit-timeout 120 \
  "$@"
