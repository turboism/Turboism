#!/usr/bin/env bash
# Selection-lag diagnostic adapter for the generic exact-host runner.
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: run-selection-lag-host-validation.sh <5203|5302> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

case "$version" in
  5302)
    fixture_src='/home/local-user/Documents/测试 混合模式.cmo3'
    fixture_sha256='57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4'
    ;;
  5203)
    fixture_src='/home/local-user/TurboismPartValidation/part52-official/part-opacity-fixture-52-final.cmo3'
    fixture_sha256='331bbb4cbdb1287f5bd063a0661d94c2860534baa7d0f76bb055ed070a21b028'
    ;;
  *)
    echo "error: version must be 5302 or 5203" >&2
    exit 2
    ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="$repo_root/build/manual-test/$worktree_id/selection-lag-validation"
probe_jar="$repo_root/build/selection-lag-probe.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

# Host-idle gate (memory #353/#558): if any unrelated Cubism / Proton process
# is running, pause and wait for the host to become idle. Never kill or clear
# unrelated processes. The generic runner re-checks golden-prefix usage before
# staging.
if [ "${1:-}" != "--dry-run" ]; then
  ssh_key="${SSH_KEY:-$HOME/.ssh/id_ed25519_validation}"
  ssh_host="${SSH_HOST:-local-user@validation-host.invalid}"
  idle_deadline=$((SECONDS + 600))
  while [ "$SECONDS" -lt "$idle_deadline" ]; do
    busy="$("${ssh_cmd:-ssh}" -i "$ssh_key" -o IdentitiesOnly=yes -o ConnectTimeout=10 \
      "$ssh_host" \
      "pgrep -af 'CubismEditor5|CECubismEditorApp|wineserver' 2>/dev/null | grep -v 'pgrep' || true" 2>/dev/null || true)"
    if [ -z "$busy" ]; then
      break
    fi
    echo "[selection-lag] host busy; waiting for unrelated Cubism/Proton processes to exit: $busy" >&2
    sleep 15
  done
  if [ -n "${busy:-}" ]; then
    echo "error: host did not become idle within 600s" >&2
    exit 1
  fi
fi

exec bash "$runner" \
  --name selection-lag \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$bundle_root" \
  --agent "$bundle_root/turboism-agent.jar" \
  --plugin "$bundle_root/plugins/parameter.jar:parameter.jar" \
  --plugin "$bundle_root/plugins/context-menu.jar:context-menu.jar" \
  --plugin "$probe_jar:selection-lag-probe.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.exitOnComplete=true' \
  --jvm-option "-Dturboism.validation.hostVersion=$version" \
  --jvm-option '-Dturboism.validation.fixtureName=fixture.cmo3' \
  --jvm-option '-Dturboism.validation.runId={TASK_ID}' \
  --jvm-option '-Dturboism.validation.thresholdMs=100' \
  --ready-marker 'SELECTION_LAG_PROBE_READY' \
  --ready-marker 'Plugin load complete' \
  --trigger 'state/dev.turboism.validation.selection-lag/exerciser.flag' \
  --result-file 'state/selection-lag-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'SELECTION_LAG_PROBE_RESULT status=FAIL' \
  --failure-marker 'SELECTION_LAG_PROBE_RESULT status=BLOCKED' \
  --failure-marker 'SELECTION_LAG_PROBE_FLAG_TIMEOUT' \
  --ready-timeout 300 \
  --result-timeout 900 \
  --exit-timeout 120 \
  "$@"
