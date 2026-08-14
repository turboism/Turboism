#!/usr/bin/env bash
# Generic exact-host Cubism validation runner.
#
# This script owns only the common host lifecycle:
#   exact identity -> task-scoped CoW prefix -> artifact/fixture staging
#   -> official CubismEditor5.bat launch -> readiness/result polling
#   -> bounded process cleanup -> machine-readable evidence collection.
#
# Feature-specific wrappers provide plugins, JVM properties, markers, triggers,
# and result files. The runner never launches Cubism's Java main class directly.
set -euo pipefail

usage() {
  cat <<'EOF'
Usage:
  run-cubism-host-validation.sh \
    --name <validation-name> \
    --version <5203|5302> \
    --bundle-root <local-directory> \
    --agent <local-agent.jar> \
    --home-config <local-config.json> \
    --plugin <local.jar[:remote-name.jar]> [--plugin ...] \
    --aux-agent <local.jar[:remote-name.jar]> [--aux-agent ...] \
    (--fixture-remote <host-path> | --fixture-local <local-path>) \
    [options]

Result modes (choose one):
  --result-marker <runtime-log PASS marker>
  --result-file <Turboism-home-relative path>
      [--result-pass-line <exact line, default status=PASS>]
      [--result-fail-line <exact line, default status=FAIL>]

Common options:
  --fixture-sha256 <expected source hash>
  --fixture-name <remote filename, default fixture.cmo3>
  --require-fixture-unchanged
  --ready-marker <runtime-log marker>        repeatable
  --failure-marker <runtime-log marker>      repeatable
  --trigger <Turboism-home-relative path>
  --jvm-option <JVM option>                  repeatable
  --run-label <label, default r1>
  --agent-timeout <seconds, default 180>
  --agent-host-class <host class to wait for, default com.live2d.cubism.CEAppCtrl>
  --ready-timeout <seconds, default 240>
  --result-timeout <seconds, default 300>
  --exit-timeout <seconds, default 120>
  --poll-seconds <seconds, default 3>
  --ssh-host <user@host, default local-user@validation-host.invalid>
  --ssh-key <path>
  --golden-prefix <host path, default /home/local-user/.proton>
  --remote-root <host path, default /home/local-user/TurboismValidation>
  --local-evidence-dir <local path>
  --display <X display, default :0>
  --proton-wrapper <host executable, default shorin-proton-wrapper>
  --proton-runner <host executable>
  --keep-prefix
  --dry-run

Placeholders in --jvm-option:
  {TASK_ID}  generated validation run ID
  {HOME}     Windows path to the task-scoped Turboism home
  {FIXTURE}  Windows path to the copied fixture
EOF
}

fail() {
  printf 'host validation: %s\n' "$*" >&2
  exit 1
}

log() {
  printf '[host-validation] %s\n' "$*"
}

require_value() {
  [ "$#" -ge 2 ] || fail "missing value for $1"
}

safe_label() {
  printf '%s' "$1" \
    | tr '[:upper:]' '[:lower:]' \
    | sed -E 's/[^a-z0-9.-]+/-/g; s/^-+//; s/-+$//; s/-{2,}/-/g'
}

require_relative_path() {
  local value="$1" label="$2"
  [ -n "$value" ] || fail "$label must not be empty"
  case "$value" in
    /*|../*|*/../*|*/..|..|*\\*) fail "$label must be a normalized relative Unix path: $value" ;;
  esac
}

require_safe_text() {
  local value="$1" label="$2"
  case "$value" in
    *$'\n'*|*$'\r'*|*'"'*|*"'"*) fail "$label contains an unsupported quote or newline" ;;
  esac
}

sha256_file() {
  sha256sum "$1" | cut -d' ' -f1
}

z_path() {
  local unix_path="$1"
  printf 'Z:%s' "${unix_path//\//\\}"
}

name=''
version=''
bundle_root=''
agent=''
home_config=''
plugins=()
aux_agents=()
fixture_remote=''
fixture_local=''
fixture_sha256=''
fixture_name='fixture.cmo3'
require_fixture_unchanged=0
ready_markers=()
failure_markers=()
result_marker=''
result_file=''
result_pass_line='status=PASS'
result_fail_line='status=FAIL'
trigger_path=''
jvm_options=()
run_label='r1'
agent_timeout=180
agent_host_class="com.live2d.cubism.CEAppCtrl"
ready_timeout=240
result_timeout=300
exit_timeout=120
poll_seconds=3
ssh_host='local-user@validation-host.invalid'
ssh_key="$HOME/.ssh/id_ed25519_validation"
golden_prefix='/home/local-user/.proton'
remote_root='/home/local-user/TurboismValidation'
local_evidence_dir=''
display=':0'
proton_wrapper='shorin-proton-wrapper'
proton_runner='/home/local-user/.local/share/Steam/compatibilitytools.d/GE-Proton10-34/proton'
keep_prefix=0
dry_run=0

while [ "$#" -gt 0 ]; do
  case "$1" in
    --name) require_value "$@"; name="$2"; shift 2 ;;
    --version) require_value "$@"; version="$2"; shift 2 ;;
    --bundle-root) require_value "$@"; bundle_root="$2"; shift 2 ;;
    --agent) require_value "$@"; agent="$2"; shift 2 ;;
    --home-config) require_value "$@"; home_config="$2"; shift 2 ;;
    --plugin) require_value "$@"; plugins+=("$2"); shift 2 ;;
    --aux-agent) require_value "$@"; aux_agents+=("$2"); shift 2 ;;
    --fixture-remote) require_value "$@"; fixture_remote="$2"; shift 2 ;;
    --fixture-local) require_value "$@"; fixture_local="$2"; shift 2 ;;
    --fixture-sha256) require_value "$@"; fixture_sha256="$2"; shift 2 ;;
    --fixture-name) require_value "$@"; fixture_name="$2"; shift 2 ;;
    --require-fixture-unchanged) require_fixture_unchanged=1; shift ;;
    --ready-marker) require_value "$@"; ready_markers+=("$2"); shift 2 ;;
    --failure-marker) require_value "$@"; failure_markers+=("$2"); shift 2 ;;
    --result-marker) require_value "$@"; result_marker="$2"; shift 2 ;;
    --result-file) require_value "$@"; result_file="$2"; shift 2 ;;
    --result-pass-line) require_value "$@"; result_pass_line="$2"; shift 2 ;;
    --result-fail-line) require_value "$@"; result_fail_line="$2"; shift 2 ;;
    --trigger) require_value "$@"; trigger_path="$2"; shift 2 ;;
    --jvm-option) require_value "$@"; jvm_options+=("$2"); shift 2 ;;
    --run-label) require_value "$@"; run_label="$2"; shift 2 ;;
    --agent-timeout) require_value "$@"; agent_timeout="$2"; shift 2 ;;
    --agent-host-class) require_value "$@"; agent_host_class="$2"; shift 2 ;;
    --ready-timeout) require_value "$@"; ready_timeout="$2"; shift 2 ;;
    --result-timeout) require_value "$@"; result_timeout="$2"; shift 2 ;;
    --exit-timeout) require_value "$@"; exit_timeout="$2"; shift 2 ;;
    --poll-seconds) require_value "$@"; poll_seconds="$2"; shift 2 ;;
    --ssh-host) require_value "$@"; ssh_host="$2"; shift 2 ;;
    --ssh-key) require_value "$@"; ssh_key="$2"; shift 2 ;;
    --golden-prefix) require_value "$@"; golden_prefix="$2"; shift 2 ;;
    --remote-root) require_value "$@"; remote_root="$2"; shift 2 ;;
    --local-evidence-dir) require_value "$@"; local_evidence_dir="$2"; shift 2 ;;
    --display) require_value "$@"; display="$2"; shift 2 ;;
    --proton-wrapper) require_value "$@"; proton_wrapper="$2"; shift 2 ;;
    --proton-runner) require_value "$@"; proton_runner="$2"; shift 2 ;;
    --keep-prefix) keep_prefix=1; shift ;;
    --dry-run) dry_run=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[ -n "$name" ] || fail "--name is required"
[ -n "$version" ] || fail "--version is required"
[ -n "$bundle_root" ] || fail "--bundle-root is required"
name="$(safe_label "$name")"
run_label="$(safe_label "$run_label")"
[ -n "$name" ] || fail "validation name becomes empty after sanitization"
[ -n "$run_label" ] || fail "run label becomes empty after sanitization"

case "$version" in
  5302)
    cubism_win='C:\Program Files\Live2D Cubism 5.3'
    cubism_rel='pfx/drive_c/Program Files/Live2D Cubism 5.3'
    reviewed_jar_sha256='988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21'
    ;;
  5203)
    cubism_win='C:\Program Files\Live2D Cubism 5.2'
    cubism_rel='pfx/drive_c/Program Files/Live2D Cubism 5.2'
    reviewed_jar_sha256='bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd'
    ;;
  *) fail "--version must be 5203 or 5302" ;;
esac

for numeric in "$agent_timeout" "$ready_timeout" "$result_timeout" "$exit_timeout" "$poll_seconds"; do
  [[ "$numeric" =~ ^[1-9][0-9]*$ ]] || fail "timeouts and poll interval must be positive integers"
done

bundle_root="$(cd "$bundle_root" 2>/dev/null && pwd)" || fail "bundle root does not exist: $bundle_root"
agent="${agent:-$bundle_root/turboism-agent.jar}"
[ -f "$agent" ] || fail "agent does not exist: $agent"
[ "${#plugins[@]}" -gt 0 ] || [ "${#aux_agents[@]}" -gt 0 ] \
  || fail "at least one --plugin or --aux-agent is required"

if [ -n "$fixture_remote" ] && [ -n "$fixture_local" ]; then
  fail "use only one of --fixture-remote or --fixture-local"
fi
if [ -z "$fixture_remote" ] && [ -z "$fixture_local" ]; then
  fail "one of --fixture-remote or --fixture-local is required"
fi
if [ -n "$fixture_local" ]; then
  [ -f "$fixture_local" ] || fail "local fixture does not exist: $fixture_local"
  fixture_local="$(cd "$(dirname "$fixture_local")" && pwd)/$(basename "$fixture_local")"
fi
if [ -n "$fixture_sha256" ]; then
  [[ "$fixture_sha256" =~ ^[0-9a-fA-F]{64}$ ]] || fail "fixture SHA-256 must contain exactly 64 hexadecimal characters"
  fixture_sha256="${fixture_sha256,,}"
fi
[[ "$fixture_name" =~ ^[A-Za-z0-9._-]+$ ]] || fail "fixture name must be a simple filename"

if [ -n "$result_marker" ] && [ -n "$result_file" ]; then
  fail "use only one result mode: --result-marker or --result-file"
fi
if [ -z "$result_marker" ] && [ -z "$result_file" ]; then
  fail "one result mode is required"
fi
[ -z "$result_file" ] || require_relative_path "$result_file" "result file"
[ -z "$trigger_path" ] || require_relative_path "$trigger_path" "trigger path"

for marker in "${ready_markers[@]}" "${failure_markers[@]}" "$result_marker" "$result_pass_line" "$result_fail_line"; do
  [ -z "$marker" ] || require_safe_text "$marker" "marker"
done
for option in "${jvm_options[@]}"; do
  require_safe_text "$option" "JVM option"
done
for value in "$fixture_remote" "$golden_prefix" "$remote_root" "$display" "$proton_wrapper" "$proton_runner"; do
  [ -z "$value" ] || require_safe_text "$value" "host path or executable"
done

resolved_plugins=()
remote_plugin_names=()
for spec in "${plugins[@]}"; do
  local_path="$spec"
  remote_name=''
  if [[ "$spec" == *:* ]]; then
    local_path="${spec%%:*}"
    remote_name="${spec#*:}"
  fi
  [ -n "$local_path" ] || fail "plugin path must not be empty"
  require_safe_text "$local_path" "plugin path"
  [ -f "$local_path" ] || fail "plugin does not exist: $local_path"
  local_path="$(cd "$(dirname "$local_path")" && pwd)/$(basename "$local_path")"
  remote_name="${remote_name:-$(basename "$local_path")}"
  [[ "$remote_name" =~ ^[A-Za-z0-9._-]+$ ]] || fail "remote plugin name must be a simple filename: $remote_name"
  for existing_name in "${remote_plugin_names[@]}"; do
    [ "$existing_name" != "$remote_name" ] || fail "duplicate remote plugin name: $remote_name"
  done
  remote_plugin_names+=("$remote_name")
  resolved_plugins+=("$local_path:$remote_name")
done

resolved_aux_agents=()
aux_agent_remote_names=()
for spec in "${aux_agents[@]}"; do
  local_path="$spec"
  remote_name=''
  if [[ "$spec" == *:* ]]; then
    local_path="${spec%%:*}"
    remote_name="${spec#*:}"
    [ -n "$remote_name" ] || fail "aux-agent remote name must not be empty"
  fi
  [ -n "$local_path" ] || fail "aux-agent path must not be empty"
  case "$local_path" in
    *:*) fail "aux-agent path must not contain ':'; use the optional remote name suffix" ;;
  esac
  require_safe_text "$local_path" "aux-agent path"
  [ -f "$local_path" ] || fail "aux-agent does not exist: $local_path"
  local_path="$(cd "$(dirname "$local_path")" && pwd)/$(basename "$local_path")"
  remote_name="${remote_name:-$(basename "$local_path")}"
  [[ "$remote_name" =~ ^[A-Za-z0-9._-]+$ ]] \
    || fail "aux-agent remote name must be a simple filename: $remote_name"
  [ "$remote_name" != "." ] && [ "$remote_name" != ".." ] \
    || fail "aux-agent remote name must not be . or .."
  for existing_name in "${aux_agent_remote_names[@]}"; do
    [ "$existing_name" != "$remote_name" ] \
      || fail "duplicate aux-agent remote name: $remote_name"
  done
  for existing_name in "${remote_plugin_names[@]}"; do
    [ "$existing_name" != "$remote_name" ] \
      || fail "aux-agent remote name conflicts with plugin name: $remote_name"
  done
  aux_agent_remote_names+=("$remote_name")
  resolved_aux_agents+=("$local_path:$remote_name")
done

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
task_id="$name-$version-$run_label-$timestamp"
task_dir="$remote_root/$name/$version-$run_label/$task_id"
home_dir="$task_dir/turboism-home"
prefix_dir="$task_dir/prefix"
evidence_dir="$task_dir/evidence"
fixture_path="$task_dir/$fixture_name"
golden_cubism="$golden_prefix/$cubism_rel"
cloned_cubism="$prefix_dir/$cubism_rel"
local_evidence_dir="${local_evidence_dir:-$repo_root/build/host-validation/$name/$version/$task_id}"

if [ "$dry_run" = 1 ]; then
  printf '%s\n' \
    "name=$name" \
    "version=$version" \
    "validationHostVersionJvmOption=-Dturboism.validation.hostVersion=$version" \
    "taskId=$task_id" \
    "bundleRoot=$bundle_root" \
    "agent=$agent" \
    "agentStage=$task_dir/turboism-agent.jar" \
    "homeConfigStage=$home_dir/config.json" \
    "fixtureRemote=$fixture_remote" \
    "fixtureLocal=$fixture_local" \
    "taskDir=$task_dir" \
    "homeDir=$home_dir" \
    "pluginStageDir=$home_dir/plugins" \
    "auxAgentDir=$task_dir/agents" \
    "ordinaryPluginCount=${#resolved_plugins[@]}" \
    "auxAgentCount=${#resolved_aux_agents[@]}" \
    "goldenCubism=$golden_cubism" \
    "clonedCubism=$cloned_cubism" \
    "resultMarker=$result_marker" \
    "resultFile=$result_file" \
    "trigger=$trigger_path" \
    "localEvidenceDir=$local_evidence_dir"
  for index in "${!resolved_plugins[@]}"; do
    printf 'plugin.%s=%s\n' "$index" "${resolved_plugins[$index]}"
  done
  printf 'turboismAgent.javaToolOption=-javaagent:%s=home=%s;timeoutSeconds=%s\n' \
    "$(z_path "$task_dir/turboism-agent.jar")" "$(z_path "$home_dir")" "$agent_timeout"
  for index in "${!resolved_aux_agents[@]}"; do
    local_path="${resolved_aux_agents[$index]%%:*}"
    remote_name="${resolved_aux_agents[$index]#*:}"
    printf 'auxAgent.%s=%s\n' "$index" "${resolved_aux_agents[$index]}"
    printf 'auxAgent.%s.remotePath=%s\n' "$index" "$task_dir/agents/$remote_name"
    printf 'auxAgent.%s.sha256=%s\n' "$index" "$(sha256_file "$local_path")"
    printf 'auxAgent.%s.javaToolOption=-javaagent:%s\n' "$index" "$(z_path "$task_dir/agents/$remote_name")"
  done
  for index in "${!jvm_options[@]}"; do
    printf 'jvmOption.%s=%s\n' "$index" "${jvm_options[$index]}"
  done
  exit 0
fi

[ -f "$ssh_key" ] || fail "SSH key does not exist: $ssh_key"

if [ "${TURBOISM_HOST_VALIDATION_LOCK_FILE+x}" = x ]; then
  [ -n "$TURBOISM_HOST_VALIDATION_LOCK_FILE" ] \
    || fail "TURBOISM_HOST_VALIDATION_LOCK_FILE must not be empty"
  lock_file="$TURBOISM_HOST_VALIDATION_LOCK_FILE"
else
  lock_file="${TMPDIR:-/tmp}/turboism-cubism-host-validation.lock"
fi
if ! exec {lock_fd}>>"$lock_file"; then
  fail "cannot open host validation lock file: $lock_file"
fi
if ! flock -n "$lock_fd"; then
  fail "host validation lock is busy: $lock_file"
fi
ssh_cmd=(ssh -i "$ssh_key" -o IdentitiesOnly=yes -o ConnectTimeout=10)
scp_cmd=(scp -i "$ssh_key" -o IdentitiesOnly=yes)
local_tmp="$(mktemp -d)"
launched=0
evidence_collected=0
success=0
wrapper_cleanup_done=0

remote_process_alive() {
  "${ssh_cmd[@]}" "$ssh_host" "test ! -s '$evidence_dir/wrapper.exit' && test -s '$evidence_dir/wrapper.pid' && kill -0 \$(cat '$evidence_dir/wrapper.pid') 2>/dev/null"
}

remote_normal_exit_evidence_seen() {
  case "$version" in
    5302)
      "${ssh_cmd[@]}" "$ssh_host" \
        "grep -Eq -- '-- successfully exited pid:[0-9]+ --' '$evidence_dir/cubism-console.txt'"
      ;;
    5203)
      local log_file
      log_file="$(latest_runtime_log)"
      [ -n "$log_file" ] || return 1
      runtime_log_contains "$log_file" 'Stopping Turboism Developer Preview' \
        && runtime_log_contains "$log_file" 'Turboism core shutdown'
      ;;
    *)
      return 1
      ;;
  esac
}

remote_record_wrapper_cleanup() {
  "${ssh_cmd[@]}" "$ssh_host" \
    "printf '%s\\n' 'cubism successful-exit marker observed; task-scoped cleanup invoked' > '$evidence_dir/wrapper.cleanup'"
}

remote_stop_process_tree() {
  "${ssh_cmd[@]}" "$ssh_host" "bash -s -- '$evidence_dir/wrapper.pid' '$prefix_dir/pfx' '$proton_runner'" <<'REMOTE' || true
set -euo pipefail
pid_file="$1"
wine_prefix="$2"
proton_runner="$3"
wineserver="$(dirname "$proton_runner")/files/bin/wineserver"
[ ! -x "$wineserver" ] || WINEPREFIX="$wine_prefix" "$wineserver" -k 2>/dev/null || true
[ -s "$pid_file" ] || exit 0
root="$(cat "$pid_file")"
kill_tree() {
  local pid="$1" child
  while read -r child; do
    [ -n "$child" ] && kill_tree "$child"
  done < <(ps -o pid= --ppid "$pid" 2>/dev/null | tr -d ' ' || true)
  kill -TERM "$pid" 2>/dev/null || true
}
kill_tree "$root"
for _ in $(seq 1 10); do
  kill -0 "$root" 2>/dev/null || exit 0
  sleep 1
done
kill -KILL "$root" 2>/dev/null || true
REMOTE
}

latest_runtime_log() {
  "${ssh_cmd[@]}" "$ssh_host" "find '$home_dir/logs/runtime' -type f -name '*.log' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -n 1 | cut -d' ' -f2-" || true
}

runtime_log_contains() {
  local log_file="$1" marker="$2"
  "${ssh_cmd[@]}" "$ssh_host" "bash -s -- '$log_file' '$marker'" <<'REMOTE'
set -euo pipefail
grep -Fq -- "$2" "$1"
REMOTE
}

result_file_contains() {
  local relative="$1" line="$2"
  "${ssh_cmd[@]}" "$ssh_host" "bash -s -- '$home_dir/$relative' '$line'" <<'REMOTE'
set -euo pipefail
[ -f "$1" ] && grep -Fxq -- "$2" "$1"
REMOTE
}

verify_staged_artifacts() {
  local phase="$1" actual expected spec local_path remote_name
  expected="$(sha256_file "$agent")"
  actual="$("${ssh_cmd[@]}" "$ssh_host" "sha256sum '$task_dir/turboism-agent.jar' | cut -d' ' -f1")"
  [ "$actual" = "$expected" ] || fail "staged agent hash mismatch"
  for spec in "${resolved_plugins[@]}"; do
    local_path="${spec%%:*}"
    remote_name="${spec#*:}"
    expected="$(sha256_file "$local_path")"
    actual="$("${ssh_cmd[@]}" "$ssh_host" "sha256sum '$home_dir/plugins/$remote_name' | cut -d' ' -f1")"
    [ "$actual" = "$expected" ] || fail "staged plugin hash mismatch: $remote_name"
  done
  if [ "${#resolved_aux_agents[@]}" -gt 0 ]; then
    local aux_hash_file
    aux_hash_file="$local_tmp/aux-agent-hashes-$phase.properties"
    : > "$aux_hash_file"
    for index in "${!resolved_aux_agents[@]}"; do
      local_path="${resolved_aux_agents[$index]%%:*}"
      remote_name="${resolved_aux_agents[$index]#*:}"
      expected="$(sha256_file "$local_path")"
      actual="$("${ssh_cmd[@]}" "$ssh_host" "sha256sum '$task_dir/agents/$remote_name' | cut -d' ' -f1")"
      printf 'auxAgent.%s.name=%s\n' "$index" "$remote_name" >> "$aux_hash_file"
      printf 'auxAgent.%s.localSha256=%s\n' "$index" "$expected" >> "$aux_hash_file"
      printf 'auxAgent.%s.stagedSha256=%s\n' "$index" "$actual" >> "$aux_hash_file"
      [ "$actual" = "$expected" ] || fail "staged aux-agent hash mismatch: $remote_name"
    done
  fi
}

collect_evidence() {
  [ "$evidence_collected" = 0 ] || return 0
  evidence_collected=1
  "${ssh_cmd[@]}" "$ssh_host" "bash -s -- '$task_dir' '$home_dir' '$evidence_dir' '$fixture_path' '$fixture_remote' '$golden_cubism' '$cloned_cubism' '$result_file'" <<'REMOTE' || true
set -u
task="$1"; home="$2"; evidence="$3"; fixture="$4"; source_fixture="$5"
golden="$6"; cloned="$7"; result_file="$8"
mkdir -p "$evidence"
runtime_log="$(find "$home/logs/runtime" -type f -name '*.log' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -n 1 | cut -d' ' -f2- || true)"
[ -z "$runtime_log" ] || cp "$runtime_log" "$evidence/turboism.log" 2>/dev/null || true
[ ! -f "$home/state/plugin-load-report.json" ] || cp "$home/state/plugin-load-report.json" "$evidence/" || true
[ ! -f "$home/state/preview-runtime-report.json" ] || cp "$home/state/preview-runtime-report.json" "$evidence/" || true
if [ -n "$result_file" ] && [ -f "$home/$result_file" ]; then
  mkdir -p "$evidence/result"
  cp "$home/$result_file" "$evidence/result/$(basename "$result_file")" || true
fi
if [ -d "$home/logs" ] || [ -d "$home/state" ]; then
  tar -C "$home" -cf "$evidence/turboism-home-logs-state.tar" \
    $( [ -d "$home/logs" ] && printf 'logs ' ) \
    $( [ -d "$home/state" ] && printf 'state ' ) 2>/dev/null || true
fi
{
  echo "fixture_after_sha256=$(sha256sum "$fixture" 2>/dev/null | cut -d' ' -f1 || true)"
  if [ -n "$source_fixture" ]; then
    echo "source_fixture_after_sha256=$(sha256sum "$source_fixture" 2>/dev/null | cut -d' ' -f1 || true)"
  fi
  echo "golden_jar_after_sha256=$(sha256sum "$golden/app/lib/Live2D_Cubism.jar" 2>/dev/null | cut -d' ' -f1 || true)"
  echo "golden_bat_after_sha256=$(sha256sum "$golden/CubismEditor5.bat" 2>/dev/null | cut -d' ' -f1 || true)"
  echo "cloned_jar_after_sha256=$(sha256sum "$cloned/app/lib/Live2D_Cubism.jar" 2>/dev/null | cut -d' ' -f1 || true)"
  echo "cloned_bat_after_sha256=$(sha256sum "$cloned/CubismEditor5.bat" 2>/dev/null | cut -d' ' -f1 || true)"
  echo "wrapper_exit=$(cat "$evidence/wrapper.exit" 2>/dev/null || true)"
} > "$evidence/final-hashes.properties"
REMOTE
  mkdir -p "$local_evidence_dir"
  "${scp_cmd[@]}" -r "$ssh_host:$evidence_dir/." "$local_evidence_dir/" >/dev/null 2>&1 || true
}

cleanup_prefix() {
  [ "$keep_prefix" = 0 ] || return 0
  "${ssh_cmd[@]}" "$ssh_host" "rm -rf -- '$prefix_dir'" || true
}

on_exit() {
  local rc=$?
  set +e
  if [ "$launched" = 1 ] && [ "$success" = 0 ] && [ "$wrapper_cleanup_done" = 0 ]; then
    remote_stop_process_tree
  fi
  collect_evidence
  if [ "$success" = 1 ]; then
    cleanup_prefix
  fi
  rm -rf "$local_tmp"
  if [ "$rc" -ne 0 ]; then
    printf 'host validation: FAILED task=%s remote=%s evidence=%s\n' \
      "$task_id" "$task_dir" "$local_evidence_dir" >&2
  fi
}
trap on_exit EXIT

log "preflight exact host identity"
identity_before="$local_tmp/identity-before.properties"
"${ssh_cmd[@]}" "$ssh_host" "bash -s -- '$golden_cubism' '$reviewed_jar_sha256' '$fixture_remote' '$fixture_sha256' '$golden_prefix' '$task_id'" > "$identity_before" <<'REMOTE'
set -euo pipefail
cubism="$1"; reviewed="$2"; fixture="$3"; fixture_expected="$4"; golden_prefix="$5"; task_id="$6"
jar="$cubism/app/lib/Live2D_Cubism.jar"
bat="$cubism/CubismEditor5.bat"
[ -f "$jar" ] || { echo "identity=FAIL missing_jar=$jar"; exit 3; }
[ -f "$bat" ] || { echo "identity=FAIL missing_bat=$bat"; exit 3; }
jar_sha="$(sha256sum "$jar" | cut -d' ' -f1)"
[ "$jar_sha" = "$reviewed" ] || { echo "identity=FAIL reviewed_jar=$reviewed actual_jar=$jar_sha"; exit 3; }
if pgrep -af 'CubismEditor5|CECubismEditorApp|wineserver' 2>/dev/null | grep -F -- "$golden_prefix" >/dev/null; then
  echo "identity=FAIL golden_prefix_in_use=$golden_prefix"
  exit 4
fi
cat <<EOF
schemaVersion=1
taskId=$task_id
goldenPrefix=$golden_prefix
hostJarSize=$(stat -c %s "$jar")
hostJarSha256=$jar_sha
officialBatSha256=$(sha256sum "$bat" | cut -d' ' -f1)
EOF
if [ -n "$fixture" ]; then
  [ -f "$fixture" ] || { echo "identity=FAIL missing_fixture=$fixture"; exit 5; }
  fixture_sha="$(sha256sum "$fixture" | cut -d' ' -f1)"
  [ -z "$fixture_expected" ] || [ "$fixture_sha" = "$fixture_expected" ] || {
    echo "identity=FAIL expected_fixture=$fixture_expected actual_fixture=$fixture_sha"
    exit 5
  }
  echo "sourceFixtureSha256=$fixture_sha"
fi
echo "identity=PASS"
REMOTE

grep -Fxq 'identity=PASS' "$identity_before" || fail "exact identity gate failed"
agent_sha256="$(sha256_file "$agent")"
{
  printf 'agentSha256=%s\n' "$agent_sha256"
  if [ -n "$fixture_local" ]; then
    local_fixture_sha="$(sha256_file "$fixture_local")"
    if [ -n "$fixture_sha256" ] && [ "$local_fixture_sha" != "$fixture_sha256" ]; then
      fail "local fixture hash mismatch: expected $fixture_sha256 got $local_fixture_sha"
    fi
    printf 'sourceFixtureSha256=%s\n' "$local_fixture_sha"
  fi
  for index in "${!resolved_plugins[@]}"; do
    local_path="${resolved_plugins[$index]%%:*}"
    remote_name="${resolved_plugins[$index]#*:}"
    printf 'plugin.%s.name=%s\n' "$index" "$remote_name"
    printf 'plugin.%s.sha256=%s\n' "$index" "$(sha256_file "$local_path")"
  done
  if [ -n "$home_config" ]; then
    printf 'homeConfigSha256=%s\n' "$(sha256_file "$home_config")" >> "$identity_before"
  fi
} >> "$identity_before"

log "creating task directory and CoW prefix clone"
"${ssh_cmd[@]}" "$ssh_host" "bash -s -- '$task_dir' '$home_dir' '$evidence_dir' '$golden_prefix' '$prefix_dir' '$cloned_cubism' '$reviewed_jar_sha256'" <<'REMOTE'
set -euo pipefail
task="$1"; home="$2"; evidence="$3"; golden="$4"; prefix="$5"; cubism="$6"; reviewed="$7"
mkdir -p "$task" "$task/agents" "$home/plugins" "$home/state" "$home/logs" "$evidence"
cp -a --reflink=always "$golden" "$prefix"
rm -f "$prefix/pfx.lock"
test -d "$prefix/pfx/drive_c/windows"
jar="$cubism/app/lib/Live2D_Cubism.jar"
bat="$cubism/CubismEditor5.bat"
[ "$(sha256sum "$jar" | cut -d' ' -f1)" = "$reviewed" ]
{
  echo "clonedJarSha256=$(sha256sum "$jar" | cut -d' ' -f1)"
  echo "clonedBatSha256=$(sha256sum "$bat" | cut -d' ' -f1)"
} > "$evidence/cloned-identity.properties"
REMOTE

"${scp_cmd[@]}" "$identity_before" "$ssh_host:$evidence_dir/identity-before.properties"
"${scp_cmd[@]}" "$agent" "$ssh_host:$task_dir/turboism-agent.jar"
if [ -n "$home_config" ]; then
  "${scp_cmd[@]}" "$home_config" "$ssh_host:$home_dir/config.json"
  staged_config_sha="$(sha256_file "$home_config")"
  "${ssh_cmd[@]}" "$ssh_host" "test -s '$home_dir/config.json' && test \"\$(sha256sum '$home_dir/config.json' | cut -d' ' -f1)\" = '$staged_config_sha'"
fi
for spec in "${resolved_plugins[@]}"; do
  local_path="${spec%%:*}"
  remote_name="${spec#*:}"
  "${scp_cmd[@]}" "$local_path" "$ssh_host:$home_dir/plugins/$remote_name"
done
for spec in "${resolved_aux_agents[@]}"; do
  local_path="${spec%%:*}"
  remote_name="${spec#*:}"
  "${scp_cmd[@]}" "$local_path" "$ssh_host:$task_dir/agents/$remote_name"
done
verify_staged_artifacts before
if [ "${#resolved_aux_agents[@]}" -gt 0 ]; then
  cat "$local_tmp/aux-agent-hashes-before.properties" >> "$identity_before"
  "${scp_cmd[@]}" "$identity_before" "$ssh_host:$evidence_dir/identity-before.properties"
  "${scp_cmd[@]}" "$local_tmp/aux-agent-hashes-before.properties" \
    "$ssh_host:$evidence_dir/aux-agent-hashes-before.properties"
fi
if [ -n "$fixture_remote" ]; then
  "${ssh_cmd[@]}" "$ssh_host" "cp --reflink=auto -- '$fixture_remote' '$fixture_path'"
else
  "${scp_cmd[@]}" "$fixture_local" "$ssh_host:$fixture_path"
fi
fixture_before_sha256="$("${ssh_cmd[@]}" "$ssh_host" "sha256sum '$fixture_path' | cut -d' ' -f1")"
source_before_sha256="$(grep '^sourceFixtureSha256=' "$identity_before" | tail -n 1 | cut -d= -f2-)"
[ -n "$source_before_sha256" ] || fail "source fixture identity is missing"
[ "$fixture_before_sha256" = "$source_before_sha256" ] || fail "copied fixture hash differs from its source"
printf 'fixtureBeforeSha256=%s\n' "$fixture_before_sha256" > "$local_tmp/fixture-before.properties"
"${scp_cmd[@]}" "$local_tmp/fixture-before.properties" "$ssh_host:$evidence_dir/fixture-before.properties"

win_home="$(z_path "$home_dir")"
win_agent="$(z_path "$task_dir/turboism-agent.jar")"
win_fixture="$(z_path "$fixture_path")"
win_console="$(z_path "$evidence_dir/cubism-console.txt")"
win_launch="$(z_path "$task_dir/launch.bat")"
cmd_unix="$prefix_dir/pfx/drive_c/windows/system32/cmd.exe"

all_jvm_options=(
  '--add-exports=java.base/jdk.internal.org.objectweb.asm=ALL-UNNAMED'
  '--add-exports=java.base/jdk.internal.org.objectweb.asm.commons=ALL-UNNAMED'
  "-Dturboism.home=$win_home"
  "-Dturboism.validation.runId=$task_id"
  "-Dturboism.validation.hostVersion=$version"
)
all_jvm_options+=("-javaagent:$win_agent=home=$win_home;timeoutSeconds=$agent_timeout;hostClass=$agent_host_class")
for spec in "${resolved_aux_agents[@]}"; do
  remote_name="${spec#*:}"
  win_aux_agent="$(z_path "$task_dir/agents/$remote_name")"
  all_jvm_options+=("-javaagent:$win_aux_agent")
done
for option in "${jvm_options[@]}"; do
  option="${option//\{TASK_ID\}/$task_id}"
  option="${option//\{HOME\}/$win_home}"
  option="${option//\{FIXTURE\}/$win_fixture}"
  all_jvm_options+=("$option")
done
java_tool_options="${all_jvm_options[*]}"
require_safe_text "$java_tool_options" "combined JAVA_TOOL_OPTIONS"

cat > "$local_tmp/launch.bat" <<BAT
@echo off
setlocal
set "JAVA_TOOL_OPTIONS=$java_tool_options"
call "$cubism_win\\CubismEditor5.bat" "$win_fixture" > "$win_console" 2>&1
exit /b %ERRORLEVEL%
BAT
cat > "$local_tmp/launch.sh" <<SH
#!/bin/sh
set -u
export DISPLAY="$display"
cd "$task_dir" || exit 1
"$proton_wrapper" -p "$prefix_dir" --runner "$proton_runner" --debug "$cmd_unix" /c "$win_launch" > "$evidence_dir/launcher.out" 2>&1
rc=\$?
printf '%s\n' "\$rc" > "$evidence_dir/wrapper.exit"
exit "\$rc"
SH
"${scp_cmd[@]}" "$local_tmp/launch.bat" "$ssh_host:$task_dir/launch.bat"
"${scp_cmd[@]}" "$local_tmp/launch.sh" "$ssh_host:$task_dir/launch.sh"
"${ssh_cmd[@]}" "$ssh_host" "chmod 700 '$task_dir/launch.sh'"

log "launching exact Cubism $version through official BAT"
"${ssh_cmd[@]}" "$ssh_host" "cd '$task_dir' || exit 1; nohup ./launch.sh </dev/null >/dev/null 2>&1 & pid=\$!; printf '%s\n' \"\$pid\" > '$evidence_dir/wrapper.pid'"
launched=1

log_file=''
if [ "${#ready_markers[@]}" -gt 0 ]; then
  log "waiting for readiness markers"
  deadline=$((SECONDS + ready_timeout))
  while [ "$SECONDS" -lt "$deadline" ]; do
    log_file="$(latest_runtime_log)"
    if [ -n "$log_file" ]; then
      ready=1
      for marker in "${ready_markers[@]}"; do
        runtime_log_contains "$log_file" "$marker" || { ready=0; break; }
      done
      [ "$ready" = 0 ] || break
    fi
    remote_process_alive || fail "host exited before readiness"
    sleep "$poll_seconds"
  done
  [ "${ready:-0}" = 1 ] || fail "readiness timeout after ${ready_timeout}s"
fi

if [ -n "$trigger_path" ]; then
  log "creating trigger $trigger_path"
  "${ssh_cmd[@]}" "$ssh_host" "mkdir -p '$home_dir/$(dirname "$trigger_path")' && touch '$home_dir/$trigger_path'"
fi

log "waiting for terminal validation result"
deadline=$((SECONDS + result_timeout))
result_passed=0
while [ "$SECONDS" -lt "$deadline" ]; do
  log_file="${log_file:-$(latest_runtime_log)}"
  if [ -n "$log_file" ]; then
    for marker in "${failure_markers[@]}"; do
      runtime_log_contains "$log_file" "$marker" && fail "failure marker observed: $marker"
    done
  fi
  if [ -n "$result_marker" ]; then
    if [ -n "$log_file" ] && runtime_log_contains "$log_file" "$result_marker"; then
      result_passed=1
      break
    fi
  else
    if result_file_contains "$result_file" "$result_fail_line"; then
      fail "result file reported failure: $result_file"
    fi
    if result_file_contains "$result_file" "$result_pass_line"; then
      result_passed=1
      break
    fi
  fi
  remote_process_alive || fail "host exited before terminal result"
  sleep "$poll_seconds"
done
[ "$result_passed" = 1 ] || fail "result timeout after ${result_timeout}s"

log "terminal PASS observed; waiting for graceful launcher exit"
deadline=$((SECONDS + exit_timeout))
while [ "$SECONDS" -lt "$deadline" ]; do
  if remote_normal_exit_evidence_seen; then
    if remote_process_alive; then
      remote_record_wrapper_cleanup
      remote_stop_process_tree
      wrapper_cleanup_done=1
    fi
    break
  fi
  remote_process_alive || break
  sleep "$poll_seconds"
done
if [ "$wrapper_cleanup_done" = 0 ]; then
  remote_process_alive && fail "launcher did not exit within ${exit_timeout}s"
fi

wrapper_exit="$("${ssh_cmd[@]}" "$ssh_host" "cat '$evidence_dir/wrapper.exit' 2>/dev/null || true")"
if [ -n "$wrapper_exit" ]; then
  [ "$wrapper_exit" = 0 ] || fail "official launcher exited with code $wrapper_exit"
elif [ "$wrapper_cleanup_done" = 0 ]; then
  fail "official launcher exited with code missing"
fi
verify_staged_artifacts after
if [ "${#resolved_aux_agents[@]}" -gt 0 ]; then
  "${scp_cmd[@]}" "$local_tmp/aux-agent-hashes-after.properties" \
    "$ssh_host:$evidence_dir/aux-agent-hashes-after.properties"
fi

source_after_sha256=''
if [ -n "$fixture_remote" ]; then
  source_after_sha256="$("${ssh_cmd[@]}" "$ssh_host" "sha256sum '$fixture_remote' | cut -d' ' -f1")"
  source_before_sha256="$(grep '^sourceFixtureSha256=' "$identity_before" | tail -n 1 | cut -d= -f2-)"
  [ "$source_after_sha256" = "$source_before_sha256" ] || fail "source fixture changed"
fi
fixture_after_sha256="$("${ssh_cmd[@]}" "$ssh_host" "sha256sum '$fixture_path' | cut -d' ' -f1")"
if [ "$require_fixture_unchanged" = 1 ] && [ "$fixture_after_sha256" != "$fixture_before_sha256" ]; then
  fail "copied fixture changed despite --require-fixture-unchanged"
fi

golden_jar_after="$("${ssh_cmd[@]}" "$ssh_host" "sha256sum '$golden_cubism/app/lib/Live2D_Cubism.jar' | cut -d' ' -f1")"
cloned_jar_after="$("${ssh_cmd[@]}" "$ssh_host" "sha256sum '$cloned_cubism/app/lib/Live2D_Cubism.jar' | cut -d' ' -f1")"
golden_bat_before="$(grep '^officialBatSha256=' "$identity_before" | cut -d= -f2-)"
cloned_bat_before="$("${ssh_cmd[@]}" "$ssh_host" "grep '^clonedBatSha256=' '$evidence_dir/cloned-identity.properties' | cut -d= -f2-")"
golden_bat_after="$("${ssh_cmd[@]}" "$ssh_host" "sha256sum '$golden_cubism/CubismEditor5.bat' | cut -d' ' -f1")"
cloned_bat_after="$("${ssh_cmd[@]}" "$ssh_host" "sha256sum '$cloned_cubism/CubismEditor5.bat' | cut -d' ' -f1")"
[ "$golden_jar_after" = "$reviewed_jar_sha256" ] || fail "golden Cubism JAR changed"
[ "$cloned_jar_after" = "$reviewed_jar_sha256" ] || fail "cloned Cubism JAR changed"
[ "$golden_bat_after" = "$golden_bat_before" ] || fail "golden Cubism launcher changed"
[ "$cloned_bat_after" = "$cloned_bat_before" ] || fail "cloned Cubism launcher changed"

collect_evidence
success=1
cleanup_prefix

log "PASS task=$task_id"
log "remote task=$task_dir"
log "local evidence=$local_evidence_dir"
