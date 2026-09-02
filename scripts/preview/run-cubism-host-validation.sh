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

# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

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
    --home-file <local-file:relative-home-path> [--home-file ...] \
    --home-dir <local-directory:relative-home-path> [--home-dir ...] \
    (--fixture-remote <host-path> | --fixture-local <local-path>) \
    [options]

Result modes (choose one):
  --result-marker <runtime-log PASS marker>
  --result-file <Turboism-home-relative path>
      [--result-pass-line <exact line, default status=PASS>]
      [--result-fail-line <exact line, default status=FAIL>]

Common options:
  --home-file <local-file:relative-home-path>   repeatable
  --home-dir <local-directory:relative-home-path> repeatable
  --remote-pre-launch <local-script>       runs on the host with task paths and launch context
  --remote-post-launch <local-script>      runs on the host after Cubism starts
  --remote-pre-cleanup <local-script>      runs before task process cleanup/evidence collection
      Hooks receive task, home, evidence, prefix, fixture, run ID, version,
      result timeout, Proton wrapper, Proton runner, and display as positional arguments.
  --fixture-sha256 <expected source hash>
  --fixture-name <remote filename suffix>
      The copied project is always prefixed with the generated validation run ID.
      By default only the source extension is retained; use this for a host-specific suffix.
  --require-fixture-unchanged
  --ready-marker <runtime-log marker>        repeatable
  --failure-marker <runtime-log marker>      repeatable
  --trigger <Turboism-home-relative path>
  --client-script <local-script[:remote-name]>
      Stage and run one task-local client after readiness. The client receives
      <Turboism-home> and <task-id> as argv[1] and argv[2].
  --jvm-option <JVM option>                  repeatable
  --windows-env <NAME=value>                 repeatable task-local launch environment
  --cubism-java <Windows executable path>    override JAVA_EXE in the task-local launch
  --cubism-java-console-marker <exact text>  require this text in Cubism console evidence
  --run-label <label, default r1>
  --agent-timeout <seconds, default 180>
  --agent-host-class <host class to wait for, default com.live2d.cubism.CEAppCtrl>
  --ready-timeout <seconds, default 240>
  --result-timeout <seconds, default 300>
  --exit-timeout <seconds, default 120>
  --poll-seconds <seconds, default 3>
  --ssh-host <user@host, or TURBOISM_HOST_VALIDATION_SSH_HOST>
  --ssh-key <path, or TURBOISM_HOST_VALIDATION_SSH_KEY>
  --golden-prefix <host path, or TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX>
  --remote-root <host path, or TURBOISM_HOST_VALIDATION_REMOTE_ROOT>
  --local-evidence-dir <local path>
  --display <X display, default :0>
  --proton-wrapper <host executable, default shorin-proton-wrapper>
  --proton-runner <host executable>
  --keep-prefix
  --dry-run

Placeholders in --jvm-option:
  {TASK_ID}      generated validation run ID
  {HOME}         Windows path to the task-scoped Turboism home
  {FIXTURE}      Windows path to the copied fixture
  {FIXTURE_NAME} copied fixture basename
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
  # These paths are later used as remote filesystem paths. Keep their grammar
  # deliberately narrower than POSIX: it makes every relative-path interpolation
  # inert even if a future remote operation regresses to shell transport.
  [[ "$value" =~ ^[A-Za-z0-9._-][A-Za-z0-9._/-]*$ ]] \
    || fail "$label must contain only ASCII letters, digits, dot, underscore, dash, and slash: $value"
  case "/$value/" in
    *'//'*) fail "$label must be a normalized relative Unix path: $value" ;;
  esac
  case "$value" in
    .|./*|*/.|*/./*|..|../*|*/..|*/../*) fail "$label must be a normalized relative Unix path: $value" ;;
  esac
}

require_safe_text() {
  local value="$1" label="$2"
  [[ ! "$value" =~ [[:cntrl:]] ]] || fail "$label contains an unsupported control character"
  case "$value" in
    *'"'*|*"'"*) fail "$label contains an unsupported quote" ;;
  esac
}

require_safe_remote_value() {
  local value="$1" label="$2" forbidden
  require_safe_text "$value" "$label"
  for forbidden in '\\' ';' '|' '&' '$' '`' '(' ')' '<' '>' '!' '#' '*' '?' '[' ']' '{' '}' '~'; do
    [[ "$value" != *"$forbidden"* ]] || fail "$label contains an unsupported shell metacharacter"
  done
}

# Sends untrusted argument values only as a Base64 payload. Remote shell code
# receives no interpolated path or marker values; it decodes the payload into
# positional parameters before using them.
remote_args_bash() {
  local encoded
  encoded="$(printf '%s\n' "$@" | base64 -w 0)"
  {
    cat <<'REMOTE_ARGS'
remote_args() {
  mapfile -t REMOTE_ARGS < <(printf '%s' "$TURBOISM_ARGS_B64" | base64 -d)
}
REMOTE_ARGS
    cat
  } | "${ssh_cmd[@]}" "$ssh_host" "TURBOISM_ARGS_B64=$encoded bash -s"
}

sha256_file() {
  sha256sum "$1" | cut -d' ' -f1
}

z_path() {
  local unix_path="$1"
  printf 'Z:%s' "${unix_path//\//\\}"
}

windows_java_tool_option() {
  local option="$1"
  case "$option" in
    *' '*|*'"'*)
      option="${option//\"/\\\"}"
      printf '"%s"' "$option"
      ;;
    *) printf '%s' "$option" ;;
  esac
}

name=''
version=''
bundle_root=''
agent=''
home_config=''
plugins=()
aux_agents=()
home_files=()
home_dirs=()
remote_pre_launch=''
remote_post_launch=''
remote_pre_cleanup=''
fixture_remote=''
fixture_local=''
fixture_sha256=''
fixture_name=''
require_fixture_unchanged=0
ready_markers=()
failure_markers=()
result_marker=''
result_file=''
result_pass_line='status=PASS'
result_fail_line='status=FAIL'
trigger_path=''
client_script=''
client_script_remote_name=''
jvm_options=()
windows_environment=()
cubism_java=''
cubism_java_console_marker=''
run_label='r1'
agent_timeout=180
agent_host_class="com.live2d.cubism.CEAppCtrl"
ready_timeout=240
result_timeout=300
exit_timeout=120
poll_seconds=3
ssh_host="$TURBOISM_HOST_VALIDATION_SSH_HOST"
ssh_key="$TURBOISM_HOST_VALIDATION_SSH_KEY"
golden_prefix="$TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX"
remote_root="$TURBOISM_HOST_VALIDATION_REMOTE_ROOT"
local_evidence_dir=''
display=':0'
proton_wrapper='shorin-proton-wrapper'
proton_runner="$TURBOISM_HOST_VALIDATION_PROTON_RUNNER"
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
    --home-file) require_value "$@"; home_files+=("$2"); shift 2 ;;
    --home-dir) require_value "$@"; home_dirs+=("$2"); shift 2 ;;
    --remote-pre-launch) require_value "$@"; remote_pre_launch="$2"; shift 2 ;;
    --remote-post-launch) require_value "$@"; remote_post_launch="$2"; shift 2 ;;
    --remote-pre-cleanup) require_value "$@"; remote_pre_cleanup="$2"; shift 2 ;;
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
    --client-script) require_value "$@"; client_script="$2"; shift 2 ;;
    --jvm-option) require_value "$@"; jvm_options+=("$2"); shift 2 ;;
    --windows-env) require_value "$@"; windows_environment+=("$2"); shift 2 ;;
    --cubism-java) require_value "$@"; cubism_java="$2"; shift 2 ;;
    --cubism-java-console-marker) require_value "$@"; cubism_java_console_marker="$2"; shift 2 ;;
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
[ -n "$ssh_host" ] || fail "validation SSH host is required; set --ssh-host or TURBOISM_HOST_VALIDATION_SSH_HOST in .env"
[ -n "$ssh_key" ] || fail "validation SSH key is required; set --ssh-key or TURBOISM_HOST_VALIDATION_SSH_KEY in .env"
[ -n "$golden_prefix" ] || fail "golden Proton prefix is required; set --golden-prefix or TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX in .env"
[ -n "$remote_root" ] || fail "remote validation root is required; set --remote-root or TURBOISM_HOST_VALIDATION_REMOTE_ROOT in .env"
[ -n "$proton_runner" ] || fail "Proton runner is required; set --proton-runner or TURBOISM_HOST_VALIDATION_PROTON_RUNNER in .env"
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
if [ -n "$fixture_name" ]; then
  [[ "$fixture_name" =~ ^[A-Za-z0-9._-]+$ ]] || fail "fixture name must be a simple filename suffix"
fi

if [ -n "$result_marker" ] && [ -n "$result_file" ]; then
  fail "use only one result mode: --result-marker or --result-file"
fi
if [ -z "$result_marker" ] && [ -z "$result_file" ]; then
  fail "one result mode is required"
fi
[ -z "$result_file" ] || require_relative_path "$result_file" "result file"
[ -z "$trigger_path" ] || require_relative_path "$trigger_path" "trigger path"
if [ -n "$client_script" ]; then
  local_path="$client_script"
  if [[ "$client_script" == *:* ]]; then
    local_path="${client_script%%:*}"
    client_script_remote_name="${client_script#*:}"
  fi
  [ -n "$local_path" ] || fail "client script path must not be empty"
  require_safe_text "$local_path" "client script path"
  [ -f "$local_path" ] || fail "client script does not exist: $local_path"
  local_path="$(cd "$(dirname "$local_path")" && pwd)/$(basename "$local_path")"
  client_script_remote_name="${client_script_remote_name:-$(basename "$local_path")}";
  [[ "$client_script_remote_name" =~ ^[A-Za-z0-9._-]+$ ]] \
    || fail "client script remote name must be a simple filename: $client_script_remote_name"
  client_script="$local_path"
fi

for marker in "${ready_markers[@]}" "${failure_markers[@]}" "$result_marker" "$result_pass_line" "$result_fail_line" "$cubism_java_console_marker"; do
  [ -z "$marker" ] || require_safe_text "$marker" "marker"
done
for option in "${jvm_options[@]}"; do
  require_safe_text "$option" "JVM option"
done
windows_environment_names=()
for assignment in "${windows_environment[@]}"; do
  require_safe_text "$assignment" "Windows environment assignment"
  [[ "$assignment" =~ ^[A-Za-z_][A-Za-z0-9_]*=.+$ ]] \
    || fail "Windows environment assignment must use NAME=value: $assignment"
  environment_name=${assignment%%=*}
  environment_value=${assignment#*=}
  environment_name_normalized=${environment_name^^}
  case "$environment_name_normalized" in
    JAVA_TOOL_OPTIONS|_JAVA_OPTIONS|JDK_JAVA_OPTIONS)
      fail "Windows environment assignment may not override $environment_name"
      ;;
  esac
  for existing_name in "${windows_environment_names[@]}"; do
    [ "$existing_name" != "$environment_name_normalized" ] \
      || fail "duplicate Windows environment name: $environment_name"
  done
  windows_environment_names+=("$environment_name_normalized")
  for forbidden in '%' '!' '^' '&' '|' '<' '>' '`' '$' ';' '"'; do
    [[ "$environment_value" != *"$forbidden"* ]] \
      || fail "Windows environment value contains an unsupported command character: $forbidden"
  done
done
for hook in "$remote_pre_launch" "$remote_post_launch" "$remote_pre_cleanup"; do
  if [ -n "$hook" ]; then
    require_safe_text "$hook" "remote hook path"
    [ -f "$hook" ] || fail "remote hook does not exist: $hook"
  fi
done
if [ -n "$cubism_java" ]; then
  require_safe_text "$cubism_java" "Cubism Java path"
  case "$cubism_java" in
    [A-Za-z]:\\*.exe) ;;
    *) fail "Cubism Java path must be an absolute Windows .exe path: $cubism_java" ;;
  esac
  [[ "$cubism_java" != */* ]] || fail "Cubism Java path must use Windows backslashes: $cubism_java"
  for forbidden in '%' '!' '^' '&' '|' '<' '>' '`' '$' ';'; do
    [[ "$cubism_java" != *"$forbidden"* ]] \
      || fail "Cubism Java path contains an unsupported command character: $forbidden"
  done
fi
require_safe_remote_value "$ssh_host" "SSH host"
[[ "$ssh_host" != -* ]] || fail "SSH host must not begin with an option prefix"
for value in "$fixture_remote" "$golden_prefix" "$remote_root" "$display" "$proton_wrapper" "$proton_runner"; do
  [ -z "$value" ] || require_safe_remote_value "$value" "host path or executable"
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

resolved_home_files=()
resolved_home_dirs=()
for spec in "${home_files[@]}"; do
  [[ "$spec" == *:* ]] || fail "home-file requires local-file:relative-home-path"
  local_path="${spec%%:*}"
  relative_path="${spec#*:}"
  [ -f "$local_path" ] || fail "home-file source does not exist: $local_path"
  require_safe_text "$local_path" "home-file source"
  require_relative_path "$relative_path" "home-file destination"
  local_path="$(cd "$(dirname "$local_path")" && pwd)/$(basename "$local_path")"
  resolved_home_files+=("$local_path:$relative_path")
done
for spec in "${home_dirs[@]}"; do
  [[ "$spec" == *:* ]] || fail "home-dir requires local-directory:relative-home-path"
  local_path="${spec%%:*}"
  relative_path="${spec#*:}"
  [ -d "$local_path" ] || fail "home-dir source does not exist: $local_path"
  require_safe_text "$local_path" "home-dir source"
  require_relative_path "$relative_path" "home-dir destination"
  local_path="$(cd "$local_path" && pwd)"
  resolved_home_dirs+=("$local_path:$relative_path")
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
if [ "${TURBOISM_HOST_VALIDATION_RUN_NONCE+x}" = x ]; then
  [[ "$TURBOISM_HOST_VALIDATION_RUN_NONCE" =~ ^[A-Za-z0-9._-]{1,32}$ ]] \
    || fail "TURBOISM_HOST_VALIDATION_RUN_NONCE must be a safe bounded label"
  run_nonce="$TURBOISM_HOST_VALIDATION_RUN_NONCE"
else
  run_nonce="$(printf '%06d' "$$")"
fi
task_id="$name-$version-$run_label-$timestamp-$run_nonce"
if [ -n "$fixture_name" ]; then
  fixture_name="$task_id-$fixture_name"
else
  source_fixture_name="${fixture_local:+$(basename "$fixture_local")}"
  source_fixture_name="${source_fixture_name:-$(basename "$fixture_remote")}"
  case "$source_fixture_name" in
    *.*) fixture_extension=".${source_fixture_name##*.}" ;;
    *) fixture_extension='' ;;
  esac
  fixture_name="$task_id$fixture_extension"
fi
[[ "$fixture_name" =~ ^[A-Za-z0-9._-]+$ ]] || fail "fixture name must be a simple filename"
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
    "fixtureName=$fixture_name" \
    "fixturePath=$fixture_path" \
    "validationFixtureNameJvmOption=-Dturboism.validation.fixtureName=$fixture_name" \
    "taskDir=$task_dir" \
    "homeDir=$home_dir" \
    "pluginStageDir=$home_dir/plugins" \
    "auxAgentDir=$task_dir/agents" \
    "ordinaryPluginCount=${#resolved_plugins[@]}" \
    "auxAgentCount=${#resolved_aux_agents[@]}" \
    "homeFileCount=${#resolved_home_files[@]}" \
    "homeDirCount=${#resolved_home_dirs[@]}" \
    "remotePreLaunch=$remote_pre_launch" \
    "remotePostLaunch=$remote_post_launch" \
    "remotePreCleanup=$remote_pre_cleanup" \
    "windowsEnvironmentCount=${#windows_environment[@]}" \
    "goldenCubism=$golden_cubism" \
    "clonedCubism=$cloned_cubism" \
    "resultMarker=$result_marker" \
    "resultFile=$result_file" \
    "trigger=$trigger_path" \
    "cubismJava=$cubism_java" \
    "cubismJavaConsoleMarker=$cubism_java_console_marker" \
    "clientScript=$client_script" \
    "clientScriptRemoteName=$client_script_remote_name" \
    "evidenceArchiver=$repo_root/scripts/preview/archive-cubism-host-evidence.sh" \
    "evidenceArchiverRemotePath=$task_dir/archive-cubism-host-evidence.sh" \
    "localEvidenceDir=$local_evidence_dir"
  for index in "${!resolved_plugins[@]}"; do
    printf 'plugin.%s=%s\n' "$index" "${resolved_plugins[$index]}"
  done
  for index in "${!resolved_home_files[@]}"; do
    printf 'homeFile.%s=%s\n' "$index" "${resolved_home_files[$index]}"
  done
  for index in "${!resolved_home_dirs[@]}"; do
    printf 'homeDir.%s=%s\n' "$index" "${resolved_home_dirs[$index]}"
  done
  if [ -n "$client_script" ]; then
    printf 'clientScript.sha256=%s\n' "$(sha256_file "$client_script")"
    printf 'clientScript.remotePath=%s\n' "$task_dir/$client_script_remote_name"
  fi
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
  dry_win_home="$(z_path "$home_dir")"
  dry_win_fixture="$(z_path "$fixture_path")"
  for index in "${!windows_environment[@]}"; do
    dry_environment="${windows_environment[$index]}"
    dry_environment="${dry_environment//\{TASK_ID\}/$task_id}"
    dry_environment="${dry_environment//\{HOME\}/$dry_win_home}"
    dry_environment="${dry_environment//\{FIXTURE\}/$dry_win_fixture}"
    dry_environment="${dry_environment//\{FIXTURE_NAME\}/$fixture_name}"
    printf 'windowsEnvironment.%s=%s\n' "$index" "$dry_environment"
  done
  for index in "${!jvm_options[@]}"; do
    dry_option="${jvm_options[$index]}"
    dry_option="${dry_option//\{TASK_ID\}/$task_id}"
    dry_option="${dry_option//\{HOME\}/$dry_win_home}"
    dry_option="${dry_option//\{FIXTURE\}/$dry_win_fixture}"
    dry_option="${dry_option//\{FIXTURE_NAME\}/$fixture_name}"
    printf 'jvmOption.%s=%s\n' "$index" "$dry_option"
    printf 'jvmOption.%s.quoted=%s\n' "$index" "$(windows_java_tool_option "$dry_option")"
  done
  exit 0
fi

[ -f "$ssh_key" ] || fail "SSH key does not exist: $ssh_key"

ssh_cmd=(ssh -i "$ssh_key" -o IdentitiesOnly=yes -o ConnectTimeout=10)
scp_cmd=(scp -i "$ssh_key" -o IdentitiesOnly=yes)
local_tmp="$(mktemp -d)"
launched=0
evidence_collected=0
success=0
wrapper_cleanup_done=0
pre_cleanup_hook_done=0

remote_process_alive() {
  remote_args_bash "$evidence_dir/wrapper.exit" "$evidence_dir/wrapper.pid" <<'REMOTE'
set -euo pipefail
remote_args
exit_file="${REMOTE_ARGS[0]}"; pid_file="${REMOTE_ARGS[1]}"
test ! -s "$exit_file" && test -s "$pid_file" && kill -0 "$(cat "$pid_file")" 2>/dev/null
REMOTE
}

remote_normal_exit_evidence_seen() {
  case "$version" in
    5302)
      remote_args_bash "$evidence_dir/cubism-console.txt" <<'REMOTE'
set -euo pipefail
remote_args
grep -Eq -- '-- successfully exited pid:[0-9]+ --' "${REMOTE_ARGS[0]}"
REMOTE
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
  remote_args_bash "$evidence_dir/wrapper.cleanup" <<'REMOTE'
set -euo pipefail
remote_args
printf '%s\n' 'cubism successful-exit marker observed; task-scoped cleanup invoked' > "${REMOTE_ARGS[0]}"
REMOTE
}

remote_stop_process_tree() {
  # Keep the task id out of this cleanup shell's process command line. Otherwise
  # the task-name scan below can discover and terminate its own coordinator
  # before it records cleanup evidence or reaches the final survivor check.
  remote_args_bash "$evidence_dir/wrapper.pid" "$prefix_dir/pfx" "$proton_runner" <<'REMOTE'
set -euo pipefail
remote_args
pid_file="${REMOTE_ARGS[0]}"
wine_prefix="${REMOTE_ARGS[1]}"
proton_runner="${REMOTE_ARGS[2]}"
wineserver="$(dirname "$proton_runner")/files/bin/wineserver"
[ ! -x "$wineserver" ] || WINEPREFIX="$wine_prefix" "$wineserver" -k 2>/dev/null || true
processes=()
if [ -s "$pid_file" ]; then
  root="$(cat "$pid_file")"
  [[ "$root" =~ ^[1-9][0-9]*$ ]] || {
    printf '%s\n' 'invalid wrapper pid' > "$(dirname "$pid_file")/task-process-cleanup.properties"
    exit 1
  }
  processes=("$root")
fi
for _ in $(seq 1 64); do
  added=0
  for parent in "${processes[@]}"; do
    while read -r child; do
      [ -n "$child" ] || continue
      if [[ " ${processes[*]} " != *" $child "* ]]; then
        processes+=("$child")
        added=1
      fi
    done < <(ps -o pid= --ppid "$parent" 2>/dev/null | tr -d ' ' || true)
  done
  [ "$added" = 1 ] || break
done
task_dir="${wine_prefix%/prefix/pfx}"
task_name="${task_dir##*/}"
append_task_processes() {
  local pid
  while read -r pid; do
    [ -n "$pid" ] || continue
    [[ " ${processes[*]} " == *" $pid "* ]] || processes+=("$pid")
  done < <(
    python3 - "$task_dir" "$wine_prefix" <<'PY'
from pathlib import Path
import sys

task = sys.argv[1]
prefix = sys.argv[2]
self_pid = str(Path('/proc/self').resolve().name)
for proc in Path('/proc').iterdir():
    if not proc.name.isdigit() or proc.name == self_pid:
        continue
    try:
        raw = (proc / 'cmdline').read_bytes()
        environ = (proc / 'environ').read_bytes()
    except (OSError, PermissionError):
        continue
    owned = task.encode() in raw or prefix.encode() in raw
    owned = owned or f'WINEPREFIX={prefix}'.encode() + b'\0' in environ
    if owned:
        print(proc.name)
PY
  )
}
append_task_processes
for ((index=${#processes[@]} - 1; index >= 0; index--)); do
  kill -TERM "${processes[$index]}" 2>/dev/null || true
done
for _ in $(seq 1 10); do
  append_task_processes
  alive=0
  for pid in "${processes[@]}"; do
    kill -0 "$pid" 2>/dev/null && alive=1
  done
  [ "$alive" = 1 ] || break
  sleep 1
done
append_task_processes
for ((index=${#processes[@]} - 1; index >= 0; index--)); do
  kill -KILL "${processes[$index]}" 2>/dev/null || true
done
for _ in $(seq 1 10); do
  alive=0
  for pid in "${processes[@]}"; do
    kill -0 "$pid" 2>/dev/null && alive=1
  done
  [ "$alive" = 1 ] || break
  sleep 1
done
mapfile -t survivors < <(
  python3 - "$task_dir" "$wine_prefix" "${processes[@]}" <<'PY'
from pathlib import Path
import sys

task = sys.argv[1]
prefix = sys.argv[2]
tracked = set(sys.argv[3:])
for proc in Path("/proc").iterdir():
    if not proc.name.isdigit():
        continue
    try:
        raw = (proc / "cmdline").read_bytes()
        cmdline = raw.replace(b"\0", b" ").decode("utf-8", "replace")
        environ = (proc / "environ").read_bytes()
    except (OSError, PermissionError):
        continue
    owned = proc.name in tracked or task.encode() in raw or prefix.encode() in raw
    owned = owned or f"WINEPREFIX={prefix}".encode() + b"\0" in environ
    if owned and proc.name != str(Path('/proc/self').resolve().name):
        print(f"{proc.name} {cmdline.strip()}")
PY
)
{
  printf 'taskDir=%s\n' "$task_dir"
  printf 'trackedProcesses=%s\n' "${#processes[@]}"
  printf 'survivors=%s\n' "${#survivors[@]}"
} > "$(dirname "$pid_file")/task-process-cleanup.properties"
if [ "${#survivors[@]}" -gt 0 ]; then
  printf '%s\n' "${survivors[@]}" > "$(dirname "$pid_file")/task-process-survivors.txt"
  return 1
fi
rm -f "$(dirname "$pid_file")/task-process-survivors.txt"
REMOTE
}

latest_runtime_log() {
  remote_args_bash "$home_dir/logs/runtime" <<'REMOTE' || true
set -euo pipefail
remote_args
find "${REMOTE_ARGS[0]}" -type f -name '*.log' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -n 1 | cut -d' ' -f2-
REMOTE
}

runtime_log_contains() {
  local log_file="$1" marker="$2"
  remote_args_bash "$log_file" "$marker" <<'REMOTE'
set -euo pipefail
remote_args
grep -Fq -- "${REMOTE_ARGS[1]}" "${REMOTE_ARGS[0]}"
REMOTE
}

result_file_contains() {
  local relative="$1" line="$2"
  remote_args_bash "$home_dir/$relative" "$line" <<'REMOTE'
set -euo pipefail
remote_args
[ -f "${REMOTE_ARGS[0]}" ] && grep -Fxq -- "${REMOTE_ARGS[1]}" "${REMOTE_ARGS[0]}"
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
  for spec in "${resolved_home_files[@]}"; do
    local_path="${spec%%:*}"
    relative_path="${spec#*:}"
    expected="$(sha256_file "$local_path")"
    actual="$("${ssh_cmd[@]}" "$ssh_host" "sha256sum '$home_dir/$relative_path' | cut -d' ' -f1")"
    [ "$actual" = "$expected" ] || fail "staged home-file hash mismatch: $relative_path"
  done
  for spec in "${resolved_home_dirs[@]}"; do
    local_path="${spec%%:*}"
    relative_path="${spec#*:}"
    local_hash="$(tar -C "$local_path" --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner -cf - . | sha256sum | cut -d' ' -f1)"
    remote_hash="$("${ssh_cmd[@]}" "$ssh_host" "tar -C '$home_dir/$relative_path' --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner -cf - . | sha256sum | cut -d' ' -f1")"
    [ "$remote_hash" = "$local_hash" ] || fail "staged home-dir hash mismatch: $relative_path"
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
archive_script="$task/archive-cubism-host-evidence.sh"
mkdir -p "$evidence"
runtime_log="$(find "$home/logs/runtime" -type f -name '*.log' -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -n 1 | cut -d' ' -f2- || true)"
[ -z "$runtime_log" ] || cp "$runtime_log" "$evidence/turboism.log" 2>/dev/null || true
[ ! -f "$home/state/plugin-load-report.json" ] || cp "$home/state/plugin-load-report.json" "$evidence/" || true
[ ! -f "$home/state/preview-runtime-report.json" ] || cp "$home/state/preview-runtime-report.json" "$evidence/" || true
if [ -n "$result_file" ] && [ -f "$home/$result_file" ]; then
  mkdir -p "$evidence/result"
  cp "$home/$result_file" "$evidence/result/$(basename "$result_file")" || true
fi
[ ! -x "$archive_script" ] || "$archive_script" "$home" "$evidence" 2>/dev/null || true
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

run_remote_hook() {
  local hook="$1"
  [ -n "$hook" ] || return 0
  if [ "$hook" = "$remote_pre_cleanup" ] && [ "$pre_cleanup_hook_done" = 1 ]; then
    return 0
  fi
  local remote_hook="$task_dir/$(basename "$hook")"
  "${scp_cmd[@]}" "$hook" "$ssh_host:$remote_hook"
  remote_args_bash \
    "$task_dir" "$home_dir" "$evidence_dir" "$prefix_dir" "$fixture_path" "$task_id" \
    "$version" "$result_timeout" "$proton_wrapper" "$proton_runner" "$display" \
    "$remote_hook" <<'REMOTE'
set -euo pipefail
remote_args
hook="${REMOTE_ARGS[11]}"
chmod 700 "$hook"
exec "$hook" "${REMOTE_ARGS[0]}" "${REMOTE_ARGS[1]}" "${REMOTE_ARGS[2]}" \
  "${REMOTE_ARGS[3]}" "${REMOTE_ARGS[4]}" "${REMOTE_ARGS[5]}" "${REMOTE_ARGS[6]}" \
  "${REMOTE_ARGS[7]}" "${REMOTE_ARGS[8]}" "${REMOTE_ARGS[9]}" "${REMOTE_ARGS[10]}"
REMOTE
  if [ "$hook" = "$remote_pre_cleanup" ]; then
    pre_cleanup_hook_done=1
  fi
}

on_exit() {
  local rc=$? cleanup_rc=0
  set +e
  if [ "$launched" = 1 ] && [ "$success" = 0 ]; then
    run_remote_hook "$remote_pre_cleanup" || cleanup_rc=1
  fi
  if [ "$launched" = 1 ] && [ "$success" = 0 ] && [ "$wrapper_cleanup_done" = 0 ]; then
    remote_stop_process_tree || cleanup_rc=1
  fi
  collect_evidence
  if [ "$success" = 1 ]; then
    cleanup_prefix
  fi
  rm -rf "$local_tmp"
  if [ "$cleanup_rc" -ne 0 ]; then
    printf 'host validation: REMOTE CLEANUP FAILED task=%s remote=%s evidence=%s\n' \
      "$task_id" "$task_dir" "$local_evidence_dir" >&2
    rc=1
  fi
  if [ "$rc" -ne 0 ]; then
    printf 'host validation: FAILED task=%s remote=%s evidence=%s\n' \
      "$task_id" "$task_dir" "$local_evidence_dir" >&2
  fi
  return "$rc"
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
  for index in "${!resolved_home_files[@]}"; do
    local_path="${resolved_home_files[$index]%%:*}"
    relative_path="${resolved_home_files[$index]#*:}"
    printf 'homeFile.%s.path=%s\n' "$index" "$relative_path"
    printf 'homeFile.%s.sha256=%s\n' "$index" "$(sha256_file "$local_path")"
  done
  for index in "${!resolved_home_dirs[@]}"; do
    local_path="${resolved_home_dirs[$index]%%:*}"
    relative_path="${resolved_home_dirs[$index]#*:}"
    printf 'homeDir.%s.path=%s\n' "$index" "$relative_path"
    printf 'homeDir.%s.sha256=%s\n' "$index" "$(tar -C "$local_path" --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner -cf - . | sha256sum | cut -d' ' -f1)"
  done
  if [ -n "$client_script" ]; then
    printf 'clientScriptName=%s\n' "$client_script_remote_name"
    printf 'clientScriptSha256=%s\n' "$(sha256_file "$client_script")"
  fi
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
"${scp_cmd[@]}" "$repo_root/scripts/preview/archive-cubism-host-evidence.sh" \
  "$ssh_host:$task_dir/archive-cubism-host-evidence.sh"
"${ssh_cmd[@]}" "$ssh_host" "chmod 700 '$task_dir/archive-cubism-host-evidence.sh'"
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
for spec in "${resolved_home_files[@]}"; do
  local_path="${spec%%:*}"
  relative_path="${spec#*:}"
  "${ssh_cmd[@]}" "$ssh_host" "mkdir -p '$home_dir/$(dirname "$relative_path")'"
  "${scp_cmd[@]}" "$local_path" "$ssh_host:$home_dir/$relative_path"
done
for spec in "${resolved_home_dirs[@]}"; do
  local_path="${spec%%:*}"
  relative_path="${spec#*:}"
  "${ssh_cmd[@]}" "$ssh_host" "mkdir -p '$home_dir/$relative_path'"
  tar -C "$local_path" -cf - . | "${ssh_cmd[@]}" "$ssh_host" "tar -C '$home_dir/$relative_path' -xf -"
done
for spec in "${resolved_aux_agents[@]}"; do
  local_path="${spec%%:*}"
  remote_name="${spec#*:}"
  "${scp_cmd[@]}" "$local_path" "$ssh_host:$task_dir/agents/$remote_name"
done
if [ -n "$client_script" ]; then
  "${scp_cmd[@]}" "$client_script" "$ssh_host:$task_dir/$client_script_remote_name"
  client_sha256="$(sha256_file "$client_script")"
  "${ssh_cmd[@]}" "$ssh_host" \
    "test \"\$(sha256sum '$task_dir/$client_script_remote_name' | cut -d' ' -f1)\" = '$client_sha256' && chmod 700 '$task_dir/$client_script_remote_name'"
fi
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

if [ -n "$cubism_java" ]; then
  remote_args_bash "$cloned_cubism/CubismEditor5.bat" "$cloned_cubism/CubismEditor5-java-override.bat" "$cubism_java" <<'REMOTE'
set -euo pipefail
remote_args
official="${REMOTE_ARGS[0]}"; override="${REMOTE_ARGS[1]}"; java_exe="${REMOTE_ARGS[2]}"
python3 - "$official" "$override" "$java_exe" <<'PY'
from pathlib import Path
import re
import sys

official = Path(sys.argv[1])
override = Path(sys.argv[2])
java_exe = sys.argv[3]
data = official.read_bytes()
pattern = rb'(?m)^set JAVA_EXE=.*(?:\r?\n)'
replacement = ('set JAVA_EXE=' + java_exe + '\r\n').encode('ascii')
updated, count = re.subn(pattern, lambda _match: replacement, data, count=1)
if count != 1:
    raise SystemExit('official launcher did not contain exactly one JAVA_EXE assignment')
override.write_bytes(updated)
PY
REMOTE
  remote_args_bash "$cloned_cubism/CubismEditor5-java-override.bat" "$cubism_java" "$evidence_dir/cubism-java.properties" <<'REMOTE'
set -euo pipefail
remote_args
launcher="${REMOTE_ARGS[0]}"; java_exe="${REMOTE_ARGS[1]}"; evidence="${REMOTE_ARGS[2]}"
grep -Fq -- "set JAVA_EXE=$java_exe" "$launcher"
{
  printf 'configuredWindowsPath=%s\n' "$java_exe"
  printf 'overrideLauncherSha256=%s\n' "$(sha256sum "$launcher" | cut -d' ' -f1)"
} > "$evidence"
REMOTE
fi

all_jvm_options=(
  '-Djava.locale.providers=CLDR,SPI'
  "-Dturboism.home=$win_home"
  "-Dturboism.validation.runId=$task_id"
  "-Dturboism.validation.hostVersion=$version"
  "-Dturboism.validation.fixtureName=$fixture_name"
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
  option="${option//\{FIXTURE_NAME\}/$fixture_name}"
  all_jvm_options+=("$option")
done
java_tool_options=''
for option in "${all_jvm_options[@]}"; do
  quoted_option="$(windows_java_tool_option "$option")"
  java_tool_options+="${java_tool_options:+ }$quoted_option"
done
[[ ! "$java_tool_options" =~ [[:cntrl:]] ]] \
  || fail "combined JAVA_TOOL_OPTIONS contains an unsupported control character"

cat > "$local_tmp/launch.bat" <<'BAT'
@echo off
setlocal
BAT
for assignment in "${windows_environment[@]}"; do
  assignment="${assignment//\{TASK_ID\}/$task_id}"
  assignment="${assignment//\{HOME\}/$win_home}"
  assignment="${assignment//\{FIXTURE\}/$win_fixture}"
  assignment="${assignment//\{FIXTURE_NAME\}/$fixture_name}"
  printf 'set "%s"\r\n' "$assignment" >> "$local_tmp/launch.bat"
done
printf 'set "JAVA_TOOL_OPTIONS=%s"\r\n' "$java_tool_options" >> "$local_tmp/launch.bat"
if [ -n "$cubism_java" ]; then
  # The official BAT assigns JAVA_EXE unconditionally, so invoke a task-local
  # text-equivalent copy with only that assignment replaced. The installed and
  # cloned official launchers stay byte-for-byte unchanged and all other vendor
  # JVM/classpath/native arguments still come from the exact reviewed BAT.
  win_task_launcher="$cubism_win\CubismEditor5-java-override.bat"
  cat >> "$local_tmp/launch.bat" <<BAT
call "$win_task_launcher" "$win_fixture" > "$win_console" 2>&1
BAT
else
  cat >> "$local_tmp/launch.bat" <<BAT
call "$cubism_win\\CubismEditor5.bat" "$win_fixture" > "$win_console" 2>&1
BAT
fi
cat >> "$local_tmp/launch.bat" <<'BAT'
exit /b %ERRORLEVEL%
BAT
run_remote_hook "$remote_pre_launch"

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
run_remote_hook "$remote_post_launch"

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
if [ -n "$client_script" ]; then
  log "running task-local validation client $client_script_remote_name"
  if ! "${ssh_cmd[@]}" "$ssh_host" \
    "'$task_dir/$client_script_remote_name' '$home_dir' '$task_id' > '$evidence_dir/client.out' 2> '$evidence_dir/client.err'"; then
    fail "task-local validation client failed"
  fi
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
if remote_process_alive; then
  log "launcher remained alive after terminal PASS; stopping the task-scoped process tree"
  "${ssh_cmd[@]}" "$ssh_host" \
    "printf '%s\n' 'terminal PASS observed; graceful close timed out; task-scoped cleanup invoked' > '$evidence_dir/wrapper.cleanup'"
  remote_stop_process_tree
  wrapper_cleanup_done=1
fi

wrapper_exit="$("${ssh_cmd[@]}" "$ssh_host" "cat '$evidence_dir/wrapper.exit' 2>/dev/null || true")"
if [ "$wrapper_cleanup_done" = 0 ]; then
  [ -n "$wrapper_exit" ] || fail "official launcher exited with code missing"
  [ "$wrapper_exit" = 0 ] || fail "official launcher exited with code $wrapper_exit"
elif [ -n "$wrapper_exit" ] && [ "$wrapper_exit" != 0 ] && [ "$wrapper_exit" != 1 ]; then
  fail "official launcher cleanup exited with unexpected code $wrapper_exit"
fi
if [ -n "$cubism_java_console_marker" ]; then
  remote_args_bash "$evidence_dir/cubism-console.txt" "$cubism_java_console_marker" <<'REMOTE' \
    || fail "Cubism Java identity marker was not observed: $cubism_java_console_marker"
set -euo pipefail
remote_args
grep -Fq -- "${REMOTE_ARGS[1]}" "${REMOTE_ARGS[0]}"
REMOTE
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

run_remote_hook "$remote_pre_cleanup"
collect_evidence
success=1
cleanup_prefix

log "PASS task=$task_id"
log "remote task=$task_dir"
log "local evidence=$local_evidence_dir"
