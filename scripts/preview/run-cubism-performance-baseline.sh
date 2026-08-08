#!/usr/bin/env bash
set -euo pipefail

variant=${1:?usage: run-cubism-performance-baseline.sh a0|a1|a2 RUN_ID TASK_DIR}
run_id=${2:?usage: run-cubism-performance-baseline.sh a0|a1|a2 RUN_ID TASK_DIR}
task=${3:?usage: run-cubism-performance-baseline.sh a0|a1|a2 RUN_ID TASK_DIR}
case "$variant" in a0|a1|a2) ;; *) echo "unknown variant: $variant" >&2; exit 2;; esac

export DISPLAY=${DISPLAY:-:0}
prefix="$task/prefix"
fixture="$task/models/heavy.cmo3"
run="$task/runs/$run_id"
install='C:\Program Files\Live2D Cubism 5.3'
run_windows="Z:${run//\//\\}"
fixture_windows="Z:${fixture//\//\\}"
rm -rf "$run"
mkdir -p "$run"

if ps -eo args= | grep -E 'Live2D_Cubism.jar|proton waitforexitandrun' | grep -v grep >/dev/null; then
  echo "another Cubism/Proton validation is active" >&2
  exit 42
fi

home="$run/home"
mkdir -p "$home/plugins" "$home/logs" "$home/state"
cat > "$home/config.json" <<EOF
{"format":"turboism.runtime.config","schemaVersion":1,"worktreeId":"perf-baseline","safeMode":false,"logLevel":"INFO","pluginDirs":["plugins"],"hooks":{"disabledIds":[],"denylistedClasses":[],"startup":{"skipUpdateCheck":false,"skipSplash":false,"skipInformation":false}}}
EOF
if [[ "$variant" == a2 ]]; then
  cp "$task/full-plugins/"*.jar "$home/plugins/"
fi

jfr="-XX:StartFlightRecording=filename=${run_windows}\\cubism.jfr,settings=profile,dumponexit=true,maxsize=512m"
agent=""
if [[ "$variant" != a0 ]]; then
  agent=" --add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED --add-exports=java.base/jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED -Dturboism.home=Z:${home//\//\\} -javaagent:Z:${task//\//\\}\\bundle\\turboism-agent.jar=home=Z:${home//\//\\};timeoutSeconds=180"
fi
cat > "$run/launch.bat" <<EOF
@echo off
setlocal
set "JAVA_TOOL_OPTIONS=$jfr$agent"
cd /d "$install"
call "$install\\CubismEditor5.bat" "$fixture_windows"
EOF

launch_epoch_ms=$(date +%s%3N)
DISPLAY=:0 nohup /usr/bin/shorin-proton-wrapper --prefix "$prefix" \
  "$prefix/pfx/drive_c/windows/system32/cmd.exe" /d /s /c "$run_windows\\launch.bat" \
  > "$run/wrapper.out" 2>&1 &
wrapper_pid=$!
printf 'variant=%s\nrun_id=%s\nlaunch_epoch_ms=%s\nwrapper_pid=%s\n' \
  "$variant" "$run_id" "$launch_epoch_ms" "$wrapper_pid" > "$run/run.properties"

pid=""
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
  kill -0 "$wrapper_pid" 2>/dev/null || { tail -100 "$run/wrapper.out" >&2; exit 3; }
  sleep 1
done
[[ -n "$pid" ]] || { echo "Cubism JVM did not start" >&2; exit 3; }
printf 'java_pid=%s\n' "$pid" >> "$run/run.properties"
nohup "$task/collect-cubism-process-metrics.sh" "$pid" "$run/metrics.csv" 1 > "$run/metrics.out" 2>&1 &
metrics_pid=$!
printf 'metrics_pid=%s\n' "$metrics_pid" >> "$run/run.properties"

main=""
for _ in $(seq 1 300); do
  while read -r candidate; do
    title=$(xdotool getwindowname "$candidate" 2>/dev/null || true)
    if [[ "$title" == *heavy.cmo3* ]]; then main=$candidate; break; fi
  done < <(xdotool search --onlyvisible --pid "$pid" 2>/dev/null || true)
  [[ -n "$main" ]] && break
  kill -0 "$pid" 2>/dev/null || { tail -100 "$run/wrapper.out" >&2; exit 3; }
  sleep 1
done
[[ -n "$main" ]] || { echo "main Cubism window did not become ready" >&2; exit 3; }
printf 'ready_epoch_ms=%s\nmain_window_id=%s\n' "$(date +%s%3N)" "$main" >> "$run/run.properties"
sleep 30

scenario_start=$(date +%s%3N)
printf 'scenario_start_epoch_ms=%s\n' "$scenario_start" >> "$run/run.properties"
"$task/run-cubism-camera-scenario.sh" "$pid" heavy.cmo3 > "$run/scenario.out"
scenario_end=$(date +%s%3N)
printf 'scenario_end_epoch_ms=%s\n' "$scenario_end" >> "$run/run.properties"
sleep 20

xdotool windowactivate --sync "$main" key --clearmodifiers alt+F4
for _ in $(seq 1 90); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
if kill -0 "$pid" 2>/dev/null; then
  echo "Cubism did not close cleanly" >&2
  exit 5
fi
sleep 5
"$task/summarize-cubism-process-metrics.py" "$run/metrics.csv" \
  "$scenario_start" "$scenario_end" > "$run/scenario-metrics-summary.json"
[[ -s "$run/cubism.jfr" ]] || { echo "JFR is missing or empty" >&2; exit 6; }
printf 'jfr_size=%s\n' "$(stat -c '%s' "$run/cubism.jfr")" >> "$run/run.properties"
cat "$run/scenario-metrics-summary.json"
