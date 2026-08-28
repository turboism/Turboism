#!/usr/bin/env bash
# Remote exact-host teardown and evidence checks for the validation-only fx bridge.
set -euo pipefail

task_dir=$1
home_dir=$2
evidence_dir=$3
prefix_dir=$4
fixture_path=$5
run_id=$6
host_version=$7
runtime_dir="$task_dir/fx-bridge-runtime"
printf '%s\n' 'requested' > "$runtime_dir/shutdown-requested"
chmod 600 "$runtime_dir/shutdown-requested"
broker_pid=''
if [ -s "$runtime_dir/broker.pid" ]; then
  broker_pid="$(cat "$runtime_dir/broker.pid")"
fi
while read -r fx_pid; do
  [ -n "$fx_pid" ] || continue
  kill -TERM -- "-$fx_pid" 2>/dev/null || kill -TERM "$fx_pid" 2>/dev/null || true
done < <(pgrep -f -- "$task_dir/fx-install/fx acp" || true)
if [ -f "$runtime_dir/fx-stderr.log" ]; then
  cp "$runtime_dir/fx-stderr.log" "$evidence_dir/fx-stderr.log"
  chmod 600 "$evidence_dir/fx-stderr.log"
fi
bridge_connected=false
if [ -f "$runtime_dir/fx-validation-bridge.connected" ]; then
  bridge_connected=true
fi
printf 'bridgeConnected=%s\n' "$bridge_connected" \
  > "$evidence_dir/fx-validation-bridge-state.properties"
chmod 600 "$evidence_dir/fx-validation-bridge-state.properties"
if [ -n "$broker_pid" ]; then
  kill -TERM "$broker_pid" 2>/dev/null || true
  for _ in $(seq 1 30); do
    kill -0 "$broker_pid" 2>/dev/null || break
    sleep 0.1
  done
  kill -KILL "$broker_pid" 2>/dev/null || true
fi
while read -r fx_pid; do
  [ -n "$fx_pid" ] || continue
  kill -KILL -- "-$fx_pid" 2>/dev/null || kill -KILL "$fx_pid" 2>/dev/null || true
done < <(pgrep -f -- "$task_dir/fx-install/fx acp" || true)

broker_alive=false
if [ -n "$broker_pid" ] && kill -0 "$broker_pid" 2>/dev/null; then
  broker_alive=true
fi
fx_alive=false
if pgrep -af -- "$task_dir/fx-install/fx acp" >/dev/null 2>&1; then
  fx_alive=true
fi
secret_leak=false
if [ -s "$runtime_dir/token" ]; then
  token="$(cat "$runtime_dir/token")"
  if grep -R -F -- "$token" "$evidence_dir" "$home_dir/logs" "$home_dir/state" \
    --exclude='turboism-home-logs-state.tar' >/dev/null 2>&1; then
    secret_leak=true
  fi
  unset token
fi

{
  printf 'schemaVersion=1\n'
  printf 'runId=%s\n' "$run_id"
  printf 'hostVersion=%s\n' "$host_version"
  printf 'brokerAlive=%s\n' "$broker_alive"
  printf 'fxAlive=%s\n' "$fx_alive"
  printf 'secretLeak=%s\n' "$secret_leak"
  if [ "$broker_alive" = false ] && [ "$fx_alive" = false ] && [ "$secret_leak" = false ]; then
    printf 'status=PASS\n'
  else
    printf 'status=FAIL\n'
  fi
} > "$evidence_dir/fx-validation-cleanup.properties"
chmod 600 "$evidence_dir/fx-validation-cleanup.properties"

rm -f "$runtime_dir/token" "$runtime_dir/fx-validation-bridge.properties" \
  "$home_dir/state/dev.turboism.plugin.turboism-with-fx/fx-validation-bridge.properties"
rm -rf "$task_dir/fx-home"

[ "$broker_alive" = false ] && [ "$fx_alive" = false ] && [ "$secret_leak" = false ]
