#!/usr/bin/env bash
set -euo pipefail

variant=${1:?usage: run-cubism-performance-probe.sh off|on RUN_ID TASK_DIR}
run_id=${2:?usage: run-cubism-performance-probe.sh off|on RUN_ID TASK_DIR}
task=${3:?usage: run-cubism-performance-probe.sh off|on RUN_ID TASK_DIR}
case "$variant" in off|on) ;; *) echo "unknown variant: $variant" >&2; exit 2;; esac

# Validate RUN_ID and resolve the run directory before anything is created.
guard_source="$(dirname "${BASH_SOURCE[0]}")/probe-run-dir-guard.sh"
[[ -r "$guard_source" ]] || guard_source="$task/probe-run-dir-guard.sh"
[[ -r "$guard_source" ]] || { echo "probe-run-dir-guard.sh is missing next to the runner" >&2; exit 64; }
# shellcheck source=/dev/null
source "$guard_source"
run=$(probe_run_dir "$task" "$run_id") || exit $?

identity_source="$(dirname "${BASH_SOURCE[0]}")/probe-process-identity.sh"
[[ -r "$identity_source" ]] || identity_source="$task/probe-process-identity.sh"
[[ -r "$identity_source" ]] || { echo "probe-process-identity.sh is missing next to the runner" >&2; exit 64; }
# shellcheck source=/dev/null
source "$identity_source"

export DISPLAY=${DISPLAY:-:0}
prefix="$task/prefix"
fixture="$task/models/heavy.cmo3"
install='C:\Program Files\Live2D Cubism 5.3'
run_windows="Z:${run//\//\\}"
fixture_windows="Z:${fixture//\//\\}"
home="$run/home"
agent_sha=$(sha256sum "$task/probe-bundle/turboism-agent.jar" | awk '{print $1}')
fixture_sha=$(sha256sum "$fixture" | awk '{print $1}')

# Fresh run directory only: the guard rejected any pre-existing candidate, and
# plain mkdir keeps creation atomic (no rm -rf anywhere in this script).
mkdir "$run"
mkdir -p "$home/plugins" "$home/logs" "$home/state" "$home/lib"
cp "$task/probe-bundle/lib/performance-probe-carrier.jar" "$home/lib/performance-probe-carrier.jar"
cat > "$home/config.json" <<EOF
{"format":"turboism.runtime.config","schemaVersion":1,"worktreeId":"perf-probe","safeMode":false,"logLevel":"INFO","pluginDirs":["plugins"],"hooks":{"disabledIds":[],"denylistedClasses":[],"startup":{"skipUpdateCheck":false,"skipSplash":false,"skipInformation":false}}}
EOF

# --- Bounded failure cleanup (host-unverified) -------------------------------
# Stops only PIDs recorded by this invocation whose exact /proc/<pid>/stat
# start tick still matches (wrapper_pid, metrics_pid, and the revalidated task
# Java PID); a reused/unverified PID is never signaled. No pkill/killall/
# wineserver. Disarmed after the normal bounded close succeeds and all
# task-started children are settled. Cleanup diagnostics stay visible.
wrapper_pid=""
metrics_pid=""
wrapper_start_ticks=""
metrics_start_ticks=""
launch_epoch_ms=0
pid=""
java_start_ticks=""
main=""
cleanup_done=0

java_pid_belongs_to_run() {
  local candidate=$1 cmd start_ticks boot_ms hz start_epoch_ms
  probe_is_numeric "$candidate" || return 1
  [[ -r "/proc/$candidate/cmdline" && -r "/proc/$candidate/stat" ]] || return 1
  cmd=$(tr '\0' ' ' < "/proc/$candidate/cmdline" 2>/dev/null || true)
  probe_cmdline_matches_fixture "$cmd" "$fixture" || return 1
  start_ticks=$(probe_start_ticks_of "$candidate") || return 1
  boot_ms=$(awk '$1=="btime" {print $2 * 1000}' /proc/stat)
  hz=$(getconf CLK_TCK)
  start_epoch_ms=$((boot_ms + start_ticks * 1000 / hz))
  (( start_epoch_ms >= launch_epoch_ms - 2000 ))
}

# True when the recorded window id still belongs to the given PID.
window_belongs_to_pid() {
  local window=$1 java=$2
  xdotool search --onlyvisible --pid "$java" 2>/dev/null | grep -Fxq "$window"
}

# Immediate full revalidation of the recorded task Java identity: exact start
# tick plus the normalized task fixture/JAR command line.
java_identity_ok() {
  probe_pid_start_ticks_match "$pid" "$java_start_ticks" && java_pid_belongs_to_run "$pid"
}

# settle_child (bounded, identity-checked TERM then KILL, reaping) comes from
# the sourced probe-process-identity.sh.
cleanup() {
  local status=$?
  [[ "$cleanup_done" -eq 1 ]] && exit "$status"
  cleanup_done=1
  echo "performance probe cleanup: stopping processes recorded for this run (host-unverified)" >&2
  # Each action is preceded by an immediate exact-start-tick + command-line
  # revalidation (and window revalidation before Alt+F4), so a PID or window
  # reused during a grace interval is never acted on.
  if java_identity_ok; then
    if [[ -n "$main" ]] && window_belongs_to_pid "$main" "$pid"; then
      xdotool windowactivate --sync "$main" key --clearmodifiers alt+F4 2>/dev/null || true
      for _ in $(seq 1 30); do java_identity_ok || break; sleep 1; done
    fi
    if java_identity_ok; then
      kill -TERM "$pid" 2>/dev/null || true
      for _ in $(seq 1 60); do java_identity_ok || break; sleep 1; done
    fi
    if java_identity_ok; then
      kill -KILL "$pid" 2>/dev/null || true
    fi
  else
    echo "performance probe cleanup: Java pid $pid not revalidated (start tick or identity mismatch); not signaling" >&2
  fi
  # Attempt both children regardless of one settlement error; preserve the
  # original exit status.
  settle_child "$metrics_pid" "$metrics_start_ticks" "metrics collector" || true
  settle_child "$wrapper_pid" "$wrapper_start_ticks" "Proton wrapper" || true
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if ps -eo args= | grep -E 'Live2D_Cubism.jar|proton waitforexitandrun' | grep -v grep >/dev/null; then
  echo "another Cubism/Proton validation is active" >&2; exit 42
fi

capture=false
[[ "$variant" == on ]] && capture=true
scenario=camera
jfr="-XX:StartFlightRecording=filename=${run_windows}\\cubism.jfr,settings=profile,dumponexit=true,maxsize=512m"
options="home=Z:${home//\//\\};timeoutSeconds=180;performanceProbeInstall=true;performanceProbeCapture=$capture;performanceProbeScenario=$scenario;performanceProbeDelaySeconds=60;performanceProbeDurationSeconds=60;performanceProbeOutput=Z:${home//\//\\}\\logs\\performance-probe.json;performanceProbeAgentSha256=$agent_sha;performanceProbeFixtureSha256=$fixture_sha;performanceProbeRunId=$run_id;performanceProbeRollbackOutput=${run_windows}\\rollback-manifest.json"
agent=" -Djava.locale.providers=CLDR,SPI -Dturboism.home=Z:${home//\//\\} -javaagent:Z:${task//\//\\}\\probe-bundle\\turboism-agent.jar=$options"
cat > "$run/launch.bat" <<EOF
@echo off
setlocal
set "JAVA_TOOL_OPTIONS=$jfr$agent"
cd /d "$install"
call "$install\\CubismEditor5.bat" "$fixture_windows" > "$run_windows\\cubism-console.txt" 2>&1
EOF

launch_epoch_ms=$(date +%s%3N)
DISPLAY=:0 nohup /usr/bin/shorin-proton-wrapper --prefix "$prefix" \
  "$prefix/pfx/drive_c/windows/system32/cmd.exe" /d /s /c "$run_windows\\launch.bat" \
  > "$run/wrapper.out" 2>&1 &
wrapper_pid=$!
wrapper_start_ticks=$(probe_start_ticks_of "$wrapper_pid")
printf 'variant=%s\nrun_id=%s\nscenario=%s\nlaunch_epoch_ms=%s\nwrapper_pid=%s\nagent_sha256=%s\nfixture_sha256=%s\n' \
  "$variant" "$run_id" "$scenario" "$launch_epoch_ms" "$wrapper_pid" "$agent_sha" "$fixture_sha" > "$run/run.properties"

for _ in $(seq 1 240); do
  mapfile -t candidates < <("$task/find-cubism-java-pid.sh" "$fixture")
  for candidate in "${candidates[@]}"; do
    [[ -r "/proc/$candidate/stat" ]] || continue
    start_ticks=$(awk '{print $22}' "/proc/$candidate/stat")
    boot_ms=$(awk '$1=="btime" {print $2 * 1000}' /proc/stat)
    hz=$(getconf CLK_TCK)
    start_epoch_ms=$((boot_ms + start_ticks * 1000 / hz))
    if ((start_epoch_ms >= launch_epoch_ms - 2000)); then
      [[ -z "$pid" ]] || { echo "multiple matching Cubism JVMs" >&2; exit 4; }
      pid=$candidate
    fi
  done
  [[ -n "$pid" ]] && break
  probe_pid_start_ticks_match "$wrapper_pid" "$wrapper_start_ticks" || { tail -100 "$run/wrapper.out" >&2; exit 3; }
  sleep 1
done
[[ -n "$pid" ]] || { echo "Cubism JVM did not start" >&2; exit 3; }
java_start_ticks=$(probe_start_ticks_of "$pid")
printf 'java_pid=%s\n' "$pid" >> "$run/run.properties"
java_identity_ok || { echo "Cubism JVM identity not verifiable after discovery" >&2; exit 12; }
nohup "$task/collect-cubism-process-metrics.sh" "$pid" "$run/metrics.csv" 1 "$java_start_ticks" > "$run/metrics.out" 2>&1 &
metrics_pid=$!
metrics_start_ticks=$(probe_start_ticks_of "$metrics_pid")
printf 'metrics_pid=%s\n' "$metrics_pid" >> "$run/run.properties"

main=""
for _ in $(seq 1 300); do
  while read -r candidate; do
    title=$(xdotool getwindowname "$candidate" 2>/dev/null || true)
    if [[ "$title" == *heavy.cmo3* ]]; then main=$candidate; break; fi
  done < <(xdotool search --onlyvisible --pid "$pid" 2>/dev/null || true)
  [[ -n "$main" ]] && break
  java_identity_ok || { tail -100 "$run/wrapper.out" >&2; exit 3; }
  sleep 1
done
[[ -n "$main" ]] || { echo "main Cubism window did not become ready" >&2; exit 3; }
printf 'ready_epoch_ms=%s\nmain_window_id=%s\n' "$(date +%s%3N)" "$main" >> "$run/run.properties"

sleep 55
# Revalidate the exact JVM identity and the recorded main window before
# sending any scenario input; fail closed on mismatch.
java_identity_ok || { echo "Cubism JVM identity changed before scenario" >&2; exit 12; }
window_belongs_to_pid "$main" "$pid" || { echo "main Cubism window no longer belongs to the JVM before scenario" >&2; exit 12; }
scenario_start=$(date +%s%3N)
printf 'scenario_start_epoch_ms=%s\n' "$scenario_start" >> "$run/run.properties"
"$task/run-cubism-camera-scenario.sh" "$pid" heavy.cmo3 > "$run/scenario.out"
scenario_end=$(date +%s%3N)
printf 'scenario_end_epoch_ms=%s\n' "$scenario_end" >> "$run/run.properties"
sleep 35

# Revalidate the exact Java identity and window immediately before sending
# keys; fail closed without sending anything on any mismatch.
if ! java_identity_ok; then
  echo "Cubism JVM identity changed before close" >&2
  exit 12
fi
if ! window_belongs_to_pid "$main" "$pid"; then
  echo "main Cubism window no longer belongs to the recorded JVM" >&2
  exit 12
fi
# Publish the rollback manifest while the JVM is still alive: on this host
# path Java shutdown hooks do not run, so the validation agent closes on a
# deterministic trigger file. Create the trigger, then wait bounded for the
# manifest (poll -s every second); abort the wait early if the recorded JVM
# is gone. The fail-closed missing-manifest check below stays the final gate.
touch "$run/rollback-manifest.json.trigger"
for _ in $(seq 1 120); do
  [[ -s "$run/rollback-manifest.json" ]] && break
  java_identity_ok || break
  sleep 1
done
xdotool windowactivate --sync "$main" key --clearmodifiers alt+F4
for _ in $(seq 1 180); do java_identity_ok || break; sleep 1; done
java_identity_ok && { echo "Cubism did not close cleanly" >&2; exit 5; }
sleep 5
# Normal bounded close succeeded: settle every task-started child. A failed
# settlement fails the run immediately (cleanup stays armed, so the EXIT
# cleanup retries and preserves this nonzero status); evidence processing
# continues only after both succeeded and cleanup is disarmed.
metrics_settled=0
wrapper_settled=0
settle_child "$metrics_pid" "$metrics_start_ticks" "metrics collector" || metrics_settled=1
settle_child "$wrapper_pid" "$wrapper_start_ticks" "Proton wrapper" || wrapper_settled=1
if [[ "$metrics_settled" -ne 0 || "$wrapper_settled" -ne 0 ]]; then
  echo "performance probe cleanup: child settlement failed; failing the run with cleanup armed" >&2
  exit 13
fi
cleanup_done=1

# Bind the isolated fixture after the run: the copy must be unchanged.
fixture_sha_after=$(sha256sum "$fixture" | awk '{print $1}')
printf 'fixture_sha256_after=%s\n' "$fixture_sha_after" >> "$run/run.properties"
[[ "$fixture_sha_after" == "$fixture_sha" ]] || { echo "isolated fixture changed during the run" >&2; exit 11; }

"$task/summarize-cubism-process-metrics.py" "$run/metrics.csv" "$scenario_start" "$scenario_end" > "$run/scenario-metrics-summary.json"
[[ -s "$run/cubism.jfr" ]] || { echo "JFR missing" >&2; exit 6; }
# Preserve every probe-related diagnostic in one task-scoped file. The agent's
# stderr routing (agent-stderr.log vs wrapper console) is host-unverified, so
# both captured sources are aggregated; the unified verifier then requires the
# install diagnostic and rejects any failure diagnostic (fail-closed).
diagnostics="$run/diagnostics.txt"
{
  cat "$home/logs/runtime/agent-stderr.log" 2>/dev/null || true
  cat "$run/wrapper.out" 2>/dev/null || true
  cat "$run/cubism-console.txt" 2>/dev/null || true
} | grep -Ei 'performance probe' > "$diagnostics" || true

# The rollback manifest is written by the validation agent from actual
# retransformation bytes (baseline, instrumented, post-close). A missing or
# rejected manifest fails the run closed here rather than admitting unproven
# restoration.
rollback_manifest="$run/rollback-manifest.json"
[[ -s "$rollback_manifest" ]] || {
  echo "rollback manifest missing: $rollback_manifest (validation agent did not publish restoration bytes)" >&2
  exit 10
}

if [[ "$variant" == on ]]; then
  report="$home/logs/performance-probe.json"
  [[ -s "$report" ]] || { echo "probe report missing" >&2; exit 9; }
  verifier_report_args=(--report "$report")
else
  verifier_report_args=()
fi
python3 "$task/verify-cubism-performance-probe.py" \
  --variant "$variant" \
  --run-id "$run_id" \
  "${verifier_report_args[@]}" \
  --diagnostics "$diagnostics" \
  --rollback-manifest "$rollback_manifest" \
  --run-properties "$run/run.properties" \
  --scenario "$scenario" \
  --agent-sha256 "$agent_sha" \
  --fixture-sha256 "$fixture_sha"
cat "$run/scenario-metrics-summary.json"
