#!/usr/bin/env bash
# Offline static regression for run-cubism-performance-probe.sh safety
# boundaries: no broad process kill, no destructive run-directory removal, and
# the recorded-PID cleanup bookkeeping must exist. Never launches a host.
set -euo pipefail

runner=$(dirname "${BASH_SOURCE[0]}")/run-cubism-performance-probe.sh
[[ -r "$runner" ]] || { echo "runner not found: $runner" >&2; exit 1; }

failures=0
for pattern in 'pkill' 'killall' 'wineserver' 'rm -rf'; do
  if grep -vE '^[[:space:]]*#' "$runner" | grep -qE "$pattern"; then
    echo "FAIL: runner contains a forbidden command: $pattern" >&2
    failures=$((failures + 1))
  fi
done
for token in 'wrapper_pid=$!' 'metrics_pid=$!' 'trap cleanup EXIT' 'cleanup_done=1' 'java_pid_belongs_to_run' 'mkdir "$run"' \
    'probe-process-identity.sh' 'probe_pid_start_ticks_match' 'settle_child' 'java_start_ticks' \
    'window_belongs_to_pid' 'java_identity_ok' 'exit 13'; do
  grep -qF "$token" "$runner" || {
    echo "FAIL: runner is missing expected cleanup/creation token: $token" >&2
    failures=$((failures + 1))
  }
done
grep -qF 'rm -rf "$run"' "$runner" && {
  echo "FAIL: runner still removes the run directory" >&2
  failures=$((failures + 1))
}
[[ "$failures" -eq 0 ]] || { echo "runner static safety: FAIL ($failures)" >&2; exit 1; }
echo "runner static safety: PASS"
