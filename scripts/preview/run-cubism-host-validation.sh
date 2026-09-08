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
    --version <5203|5302|5303> \
    --bundle-root <local-directory> \
    --agent <local-agent.jar> \
    --home-config <local-config.json> \
    --plugin <local.jar[:task-name.jar]> [--plugin ...] \
    --aux-agent <local.jar[:task-name.jar]> [--aux-agent ...] \
    --home-file <local-file:relative-home-path> [--home-file ...] \
    --home-dir <local-directory:relative-home-path> [--home-dir ...] \
    (--fixture-host|--fixture-remote <local-path> | --fixture-local <local-path>) \
    [options]

Result modes (choose one):
  --result-marker <runtime-log PASS marker>
  --result-file <Turboism-home-relative path>
      [--result-pass-line <exact line, default status=PASS>]
      [--result-fail-line <exact line, default status=FAIL>]

Local-only options:
  --host-root <local task root>
  --remote-root <local task root>       compatibility alias for --host-root
  --fixture-host <local fixture>         compatibility alias for --fixture-remote
  --golden-prefix <local Proton prefix>
  --local-evidence-dir <local path>
  --display <X display, default :0>
  --proton-wrapper <local executable, default shorin-proton-wrapper>
  --proton-runner <local executable>
  --prepare-dir <path>                  write runner-request.json and exit
  --transport local                     accepted compatibility spelling

Common options:
  --home-file <local-file:relative-home-path>   repeatable
  --home-dir <local-directory:relative-home-path> repeatable
  --remote-pre-launch <local-script>       runs locally before launch
  --remote-post-launch <local-script>      runs locally after Cubism starts
  --remote-pre-cleanup <local-script>      runs locally before cleanup/evidence
  --remote-pre-launch-background           manage a background pre-launch hook
  --remote-pre-launch-args-only            pass only explicit hook args to that hook
  --remote-pre-launch-arg <value>          repeatable hook argument
      Hooks normally receive task, home, evidence, prefix, fixture, run ID, version,
      result timeout, Proton wrapper, Proton runner, and display as positional arguments.
  --fixture-sha256 <expected source hash>
  --fixture-name <task filename suffix>
      The copied project is always prefixed with the generated validation run ID.
  --require-fixture-unchanged
  --ready-marker <runtime-log marker>        repeatable
  --failure-marker <runtime-log marker>      repeatable
  --trigger <Turboism-home-relative path>
  --client-script <local-script[:task-name]>
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
  --keep-prefix
  --dry-run

Migration errors:
  --ssh-host and --ssh-key are rejected; this Runner no longer supports SSH/SCP.

Placeholders in --jvm-option/--windows-env/--remote-pre-launch-arg:
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
# Execute the reviewed shell payload locally with encoded positional arguments.
# The encoding keeps task paths out of the child command line while preserving
# the old heredoc call shape used by process/evidence helpers.
remote_args_bash() {
  local encoded
  encoded="$(printf '%s\n' "$@" | base64 -w 0)"
  {
    cat <<'LOCAL_ARGS'
remote_args() {
  mapfile -t REMOTE_ARGS < <(printf '%s' "$TURBOISM_ARGS_B64" | base64 -d)
}
remote_args
set -- "${REMOTE_ARGS[@]}"
LOCAL_ARGS
    cat
  } | TURBOISM_ARGS_B64="$encoded" bash -s
}

sha256_file() {
  sha256sum "$1" | cut -d' ' -f1
}

local_path_canonical() {
  realpath -m -- "$1"
}

local_path_is_descendant() {
  local child="$1" parent="$2"
  if [ "$parent" = / ]; then
    [ "$child" != / ] && [[ "$child" == /* ]]
    return
  fi
  [[ "$child" == "$parent"/* ]]
}

local_assert_no_symlink_ancestors() {
  local destination="$1" current=/ part
  local lexical="$destination"
  case "$lexical" in
    /*) ;;
    *) lexical="$PWD/$lexical" ;;
  esac
  IFS=/ read -r -a local_path_parts <<< "${lexical#/}"
  for part in "${local_path_parts[@]}"; do
    case "$part" in
      ''|.) continue ;;
      ..)
        [ "$current" = / ] || current="${current%/*}"
        [ -n "$current" ] || current=/ ;;
      *)
        if [ "$current" = / ]; then current="/$part"; else current="$current/$part"; fi
        [ ! -L "$current" ] || fail "local destination has a symlink ancestor: $current"
        ;;
    esac
  done
  unset local_path_parts
}

local_assert_destination_safe() {
  local destination="$1" unsafe links
  local_assert_no_symlink_ancestors "$destination"
  [ ! -L "$destination" ] || fail "local destination is a symlink: $destination"
  if [ -f "$destination" ]; then
    links="$(stat -c %h -- "$destination")" || fail "cannot inspect local destination: $destination"
    [[ "$links" =~ ^[0-9]+$ && "$links" -le 1 ]] || fail "local destination is hardlinked: $destination"
    return 0
  fi
  [ -d "$destination" ] || return 0
  unsafe="$(find -P "$destination" -mindepth 1 \( -type l -o \( -type f -links +1 \) \) -print -quit)" \
    || fail "cannot inspect local destination tree: $destination"
  [ -z "$unsafe" ] || fail "local destination tree contains a symlink or hardlink: $unsafe"
}

local_assert_copy_safe() {
  local source="$1" destination="$2" recursive="${3:-0}" source_abs destination_abs
  source_abs="$(local_path_canonical "$source")" || fail "cannot canonicalize local source: $source"
  destination_abs="$(local_path_canonical "$destination")" || fail "cannot canonicalize local destination: $destination"
  [ "$source_abs" != "$destination_abs" ] || fail "local copy source-equals-destination: $source"
  if [ -e "$source" ] && [ -e "$destination" ] && [ "$source" -ef "$destination" ]; then
    fail "local copy source and destination alias: $source"
  fi
  if [ "$recursive" = 1 ]; then
    local_path_is_descendant "$destination_abs" "$source_abs" \
      && fail "local copy destination inside source: $destination"
    local_path_is_descendant "$source_abs" "$destination_abs" \
      && fail "local copy source inside destination: $source"
  fi
  local_assert_destination_safe "$destination"
}

local_prepare_directory() {
  local destination="$1"
  local_assert_destination_safe "$destination"
  mkdir -p -- "$destination"
}

local_copy_to() {
  local recursive=0 source destination
  if [ "${1:-}" = --recursive ]; then recursive=1; shift; fi
  [ "$#" -eq 2 ] || fail "internal local copy-to argument error"
  source="$1"; destination="$2"
  local_assert_copy_safe "$source" "$destination" "$recursive"
  if [ "$recursive" = 1 ]; then cp -a -- "$source" "$destination"; else cp -- "$source" "$destination"; fi
}

local_copy_from() {
  local recursive=0 source destination
  if [ "${1:-}" = --recursive ]; then recursive=1; shift; fi
  [ "$#" -eq 2 ] || fail "internal local copy-from argument error"
  source="$1"; destination="$2"
  local_assert_copy_safe "$source" "$destination" "$recursive"
  if [ "$recursive" = 1 ]; then cp -a -- "$source" "$destination"; else cp -- "$source" "$destination"; fi
}

local_copy_dir_contents_to() {
  local source="$1" destination="$2"
  local_assert_copy_safe "$source/." "$destination" 1
  cp -a -- "$source/." "$destination/"
}

local_copy_host_file() {
  local source="$1" destination="$2"
  local_assert_copy_safe "$source" "$destination" 0
  cp --reflink=auto -- "$source" "$destination"
}

local_remove_tree() {
  local target="$1"
  local_assert_no_symlink_ancestors "$target"
  [ ! -L "$target" ] || fail "local cleanup target is a symlink: $target"
  rm -rf -- "$target"
}

transport_command() {
  # Command strings are assembled only from validated, runner-owned values.
  bash -c "$1"
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
remote_pre_launch_background=0
remote_pre_launch_args_only=0
remote_pre_launch_args=()
fixture_host=''
fixture_local=''
fixture_sha256=''
fixture_name=''
fixture_name_suffix=''
fixture_name_explicit=0
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
golden_prefix="$TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX"
host_root="${TURBOISM_HOST_VALIDATION_HOST_ROOT:-${TURBOISM_HOST_VALIDATION_REMOTE_ROOT:-}}"
local_evidence_dir=''
display=':0'
proton_wrapper='shorin-proton-wrapper'
proton_runner="$TURBOISM_HOST_VALIDATION_PROTON_RUNNER"
prepare_dir=''
transport="${TURBOISM_HOST_VALIDATION_TRANSPORT:-local}"
dry_run=0
keep_prefix=0

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
    --remote-pre-launch-background) remote_pre_launch_background=1; shift ;;
    --remote-pre-launch-args-only) remote_pre_launch_args_only=1; shift ;;
    --remote-pre-launch-arg) require_value "$@"; remote_pre_launch_args+=("$2"); shift 2 ;;
    --fixture-host|--fixture-remote) require_value "$@"; fixture_host="$2"; shift 2 ;;
    --fixture-local) require_value "$@"; fixture_local="$2"; shift 2 ;;
    --fixture-sha256) require_value "$@"; fixture_sha256="$2"; shift 2 ;;
    --fixture-name) require_value "$@"; fixture_name="$2"; fixture_name_explicit=1; shift 2 ;;
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
    --transport) require_value "$@"; transport="$2"; shift 2 ;;
    --ssh-host|--ssh-key)
      fail "$1 is no longer supported in the local-only Runner; remove SSH/SCP options and use --host-root/--fixture-host"
      ;;
    --golden-prefix) require_value "$@"; golden_prefix="$2"; shift 2 ;;
    --host-root|--remote-root) require_value "$@"; host_root="$2"; shift 2 ;;
    --local-evidence-dir) require_value "$@"; local_evidence_dir="$2"; shift 2 ;;
    --display) require_value "$@"; display="$2"; shift 2 ;;
    --proton-wrapper) require_value "$@"; proton_wrapper="$2"; shift 2 ;;
    --proton-runner) require_value "$@"; proton_runner="$2"; shift 2 ;;
    --prepare-dir) require_value "$@"; prepare_dir="$2"; shift 2 ;;
    --keep-prefix) keep_prefix=1; shift ;;
    --dry-run) dry_run=1; shift ;;
    -h|--help) usage; exit 0 ;;
    *) fail "unknown argument: $1" ;;
  esac
done

[ -n "$name" ] || fail "--name is required"
[ -n "$version" ] || fail "--version is required"
[ -n "$bundle_root" ] || fail "--bundle-root is required"
case "$transport" in
  local|'') ;;
  remote|ssh) fail "remote/SSH transport is no longer supported; this Runner is local-only" ;;
  *) fail "unsupported transport: $transport (expected local)" ;;
esac
[ -n "$golden_prefix" ] || fail "golden Proton prefix is required; set --golden-prefix or TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX in .env"
[ -n "$host_root" ] || fail "local validation root is required; set --host-root or TURBOISM_HOST_VALIDATION_HOST_ROOT in .env"
[ -n "$proton_runner" ] || fail "Proton runner is required; set --proton-runner or TURBOISM_HOST_VALIDATION_PROTON_RUNNER in .env"
name="$(safe_label "$name")"
run_label="$(safe_label "$run_label")"
[ -n "$name" ] || fail "validation name becomes empty after sanitization"
[ -n "$run_label" ] || fail "run label becomes empty after sanitization"

case "$version" in
  5303)
    cubism_win='C:\Program Files\Live2D Cubism 5.3.03'
    cubism_rel='pfx/drive_c/Program Files/Live2D Cubism 5.3.03'
    reviewed_jar_sha256='bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166'
    ;;
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
  *) fail "--version must be 5203, 5302, or 5303" ;;
esac

for numeric in "$agent_timeout" "$ready_timeout" "$result_timeout" "$exit_timeout" "$poll_seconds"; do
  [[ "$numeric" =~ ^[1-9][0-9]*$ ]] || fail "timeouts and poll interval must be positive integers"
done

bundle_root="$(cd "$bundle_root" 2>/dev/null && pwd)" || fail "bundle root does not exist: $bundle_root"
agent="${agent:-$bundle_root/turboism-agent.jar}"
[ -f "$agent" ] || fail "agent does not exist: $agent"
agent="$(cd "$(dirname "$agent")" && pwd -P)/$(basename "$agent")"
[ "${#plugins[@]}" -gt 0 ] || [ "${#aux_agents[@]}" -gt 0 ] \
  || fail "at least one --plugin or --aux-agent is required"

for value in "$golden_prefix" "$host_root" "$display" "$proton_wrapper" "$proton_runner"; do
  [ -z "$value" ] || require_safe_remote_value "$value" "local path or executable"
done
# Runtime locations are fixed now, never resolved against the worker's cwd.
for path_variable in golden_prefix host_root proton_runner; do
  printf -v "$path_variable" '%s' "$(python3 - "${!path_variable}" <<'PY'
import os, sys
print(os.path.abspath(sys.argv[1]))
PY
)"
done
if [ "$dry_run" = 0 ]; then
  if [[ "$proton_wrapper" != */* ]]; then
    proton_wrapper="$(command -v -- "$proton_wrapper")" || fail "Proton wrapper executable not found"
  fi
  proton_wrapper="$(python3 - "$proton_wrapper" <<'PY'
import os, sys
print(os.path.abspath(sys.argv[1]))
PY
)"
fi
if [ -n "$home_config" ]; then
  require_safe_text "$home_config" "home config path"
  [ -f "$home_config" ] || fail "home config does not exist: $home_config"
  home_config="$(cd "$(dirname "$home_config")" && pwd)/$(basename "$home_config")"
fi

if [ -n "$fixture_host" ] && [ -n "$fixture_local" ]; then
  fail "use only one of --fixture-host/--fixture-remote or --fixture-local"
fi
if [ -z "$fixture_host" ] && [ -z "$fixture_local" ]; then
  fail "one of --fixture-host/--fixture-remote or --fixture-local is required"
fi
if [ -n "$fixture_host" ]; then
  require_safe_text "$fixture_host" "fixture host path"
  [ -f "$fixture_host" ] || fail "fixture host path does not exist: $fixture_host"
  fixture_host="$(cd "$(dirname "$fixture_host")" && pwd)/$(basename "$fixture_host")"
fi
if [ -n "$fixture_local" ]; then
  require_safe_text "$fixture_local" "local fixture path"
  [ -f "$fixture_local" ] || fail "local fixture does not exist: $fixture_local"
  fixture_local="$(cd "$(dirname "$fixture_local")" && pwd)/$(basename "$fixture_local")"
fi
if [ -n "$fixture_sha256" ]; then
  [[ "$fixture_sha256" =~ ^[0-9a-fA-F]{64}$ ]] \
    || fail "fixture SHA-256 must contain exactly 64 hexadecimal characters"
  fixture_sha256="${fixture_sha256,,}"
fi
if [ -n "$fixture_name" ]; then
  [[ "$fixture_name" =~ ^[A-Za-z0-9._-]+$ ]] \
    || fail "fixture name must be a simple filename suffix"
  fixture_name_suffix="$fixture_name"
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
    || fail "client script task name must be a simple filename: $client_script_remote_name"
  client_script="$local_path"
fi

for marker in "${ready_markers[@]}" "${failure_markers[@]}" "$result_marker" "$result_pass_line" "$result_fail_line" "$cubism_java_console_marker"; do
  [ -z "$marker" ] || require_safe_text "$marker" "marker"
done
for option in "${jvm_options[@]}" "${remote_pre_launch_args[@]}"; do
  require_safe_text "$option" "JVM or hook option"
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
      fail "Windows environment assignment may not override $environment_name" ;;
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
    require_safe_text "$hook" "local hook path"
    [ -f "$hook" ] || fail "local hook does not exist: $hook"
    hook="$(cd "$(dirname "$hook")" && pwd)/$(basename "$hook")"
  fi
done
if [ "$remote_pre_launch_background" = 1 ] && [ -z "$remote_pre_launch" ]; then
  fail "--remote-pre-launch-background requires --remote-pre-launch"
fi
if [ "$remote_pre_launch_args_only" = 1 ] && [ -z "$remote_pre_launch" ]; then
  fail "--remote-pre-launch-args-only requires --remote-pre-launch"
fi

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
queue_admission_json=''
supervisor_cleanup=0
if [ "$dry_run" = 0 ] && [ -z "$prepare_dir" ] && {
  [ "${TURBOISM_QUEUE_ADMISSION_FD+x}" = x ] || [ "${TURBOISM_QUEUE_JOB_ID+x}" = x ] \
    || [ "${TURBOISM_QUEUE_ATTEMPT_ID+x}" = x ] || [ "${TURBOISM_QUEUE_RUN_ID+x}" = x ];
}; then
  # Verify inherited lock, durable attempt, live supervisor and ancestry before
  # using the controlled run ID or creating any task/prefix/hook side effects.
  queue_admission_json="$(python3 "$repo_root/scripts/preview/host_validation.py" _admit)" \
    || fail "worker admission rejected"
  supervisor_cleanup="$(python3 - "$queue_admission_json" <<'PY'
import json, sys
print(1 if json.loads(sys.argv[1]).get('cleanupOwner') == 'supervisor' else 0)
PY
)"
fi
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
if [ "${TURBOISM_QUEUE_RUN_ID+x}" = x ] && [ -n "$TURBOISM_QUEUE_RUN_ID" ]; then
  [[ "$TURBOISM_QUEUE_RUN_ID" =~ ^[A-Za-z0-9._-]{1,128}$ ]] \
    || fail "TURBOISM_QUEUE_RUN_ID must be a safe bounded run ID"
  run_id="$TURBOISM_QUEUE_RUN_ID"
  task_id="$run_id"
elif [ "${TURBOISM_HOST_VALIDATION_RUN_NONCE+x}" = x ]; then
  [[ "$TURBOISM_HOST_VALIDATION_RUN_NONCE" =~ ^[A-Za-z0-9._-]{1,32}$ ]] \
    || fail "TURBOISM_HOST_VALIDATION_RUN_NONCE must be a safe bounded label"
  run_nonce="$TURBOISM_HOST_VALIDATION_RUN_NONCE"
  run_id="$name-$version-$run_label-$timestamp-$run_nonce"
  task_id="$run_id"
else
  run_nonce="$(printf '%06d' "$$")"
  run_id="$name-$version-$run_label-$timestamp-$run_nonce"
  task_id="$run_id"
fi

if [ -n "$fixture_name" ]; then
  fixture_name_suffix="$fixture_name"
else
  source_fixture_name="${fixture_local:+$(basename "$fixture_local")}"
  source_fixture_name="${source_fixture_name:-$(basename "$fixture_host")}";
  case "$source_fixture_name" in
    *.*) fixture_name_suffix=".${source_fixture_name##*.}" ;;
    *) fixture_name_suffix='' ;;
  esac
fi
if [ "$fixture_name_explicit" = 1 ]; then
  fixture_name="$task_id-$fixture_name_suffix"
else
  fixture_name="$task_id$fixture_name_suffix"
fi
[[ "$fixture_name" =~ ^[A-Za-z0-9._-]+$ ]] || fail "fixture name must be a simple filename"

if [ -n "$remote_pre_launch" ]; then
  remote_pre_launch="$(cd "$(dirname "$remote_pre_launch")" && pwd)/$(basename "$remote_pre_launch")"
fi
if [ -n "$remote_post_launch" ]; then
  remote_post_launch="$(cd "$(dirname "$remote_post_launch")" && pwd)/$(basename "$remote_post_launch")"
fi
if [ -n "$remote_pre_cleanup" ]; then
  remote_pre_cleanup="$(cd "$(dirname "$remote_pre_cleanup")" && pwd)/$(basename "$remote_pre_cleanup")"
fi

task_dir="$host_root/$name/$version-$run_label/$task_id"
home_dir="$task_dir/turboism-home"
prefix_dir="$task_dir/prefix"
evidence_dir="$task_dir/evidence"
fixture_path="$task_dir/$fixture_name"
golden_cubism="$golden_prefix/$cubism_rel"
cloned_cubism="$prefix_dir/$cubism_rel"
local_evidence_dir="${local_evidence_dir:-$repo_root/build/host-validation/$name/$version/$task_id}"

normalized_argv=(
  --name "$name" --version "$version" --bundle-root "$bundle_root" --agent "$agent"
  --fixture-sha256 "$fixture_sha256"
  --golden-prefix "$golden_prefix" --host-root "$host_root"
  --local-evidence-dir "$local_evidence_dir" --display "$display"
  --proton-wrapper "$proton_wrapper" --proton-runner "$proton_runner"
  --run-label "$run_label" --agent-timeout "$agent_timeout"
  --agent-host-class "$agent_host_class" --ready-timeout "$ready_timeout"
  --result-timeout "$result_timeout" --exit-timeout "$exit_timeout"
  --poll-seconds "$poll_seconds"
)
[ "$fixture_name_explicit" = 1 ] && normalized_argv+=(--fixture-name "$fixture_name_suffix")
if [ -n "$home_config" ]; then normalized_argv+=(--home-config "$home_config"); fi
for spec in "${resolved_plugins[@]}"; do normalized_argv+=(--plugin "$spec"); done
for spec in "${resolved_aux_agents[@]}"; do normalized_argv+=(--aux-agent "$spec"); done
for spec in "${resolved_home_files[@]}"; do normalized_argv+=(--home-file "$spec"); done
for spec in "${resolved_home_dirs[@]}"; do normalized_argv+=(--home-dir "$spec"); done
if [ -n "$fixture_host" ]; then normalized_argv+=(--fixture-host "$fixture_host"); else normalized_argv+=(--fixture-local "$fixture_local"); fi
if [ -n "$remote_pre_launch" ]; then normalized_argv+=(--remote-pre-launch "$remote_pre_launch"); fi
if [ -n "$remote_post_launch" ]; then normalized_argv+=(--remote-post-launch "$remote_post_launch"); fi
if [ -n "$remote_pre_cleanup" ]; then normalized_argv+=(--remote-pre-cleanup "$remote_pre_cleanup"); fi
[ "$remote_pre_launch_background" = 1 ] && normalized_argv+=(--remote-pre-launch-background)
[ "$remote_pre_launch_args_only" = 1 ] && normalized_argv+=(--remote-pre-launch-args-only)
for hook_arg in "${remote_pre_launch_args[@]}"; do normalized_argv+=(--remote-pre-launch-arg "$hook_arg"); done
[ "$require_fixture_unchanged" = 1 ] && normalized_argv+=(--require-fixture-unchanged)
for marker in "${ready_markers[@]}"; do normalized_argv+=(--ready-marker "$marker"); done
for marker in "${failure_markers[@]}"; do normalized_argv+=(--failure-marker "$marker"); done
if [ -n "$result_marker" ]; then
  normalized_argv+=(--result-marker "$result_marker")
else
  normalized_argv+=(--result-file "$result_file" --result-pass-line "$result_pass_line" --result-fail-line "$result_fail_line")
fi
[ -n "$trigger_path" ] && normalized_argv+=(--trigger "$trigger_path")
[ -n "$client_script" ] && normalized_argv+=(--client-script "$client_script:$client_script_remote_name")
for option in "${jvm_options[@]}"; do normalized_argv+=(--jvm-option "$option"); done
for assignment in "${windows_environment[@]}"; do normalized_argv+=(--windows-env "$assignment"); done
[ -n "$cubism_java" ] && normalized_argv+=(--cubism-java "$cubism_java")
[ -n "$cubism_java_console_marker" ] && normalized_argv+=(--cubism-java-console-marker "$cubism_java_console_marker")
[ "$keep_prefix" = 1 ] && normalized_argv+=(--keep-prefix)
normalized_argv+=(--transport local)

write_runner_request() {
  local output="$prepare_dir/runner-request.json" temporary
  local_prepare_directory "$prepare_dir"
  local_assert_destination_safe "$output"
  temporary="$(mktemp "$prepare_dir/.runner-request.json.XXXXXX")"
  python3 - "$temporary" -- "${normalized_argv[@]}" <<'PY'
import json
import os
import sys
from pathlib import Path

target = Path(sys.argv[1])
separator = sys.argv.index("--", 2)
argv = sys.argv[separator + 1:]
payload = {"schemaVersion": 1, "argv": argv, "environment": {}}
target.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n", encoding="utf-8")
os.replace(target, Path(sys.argv[1]).with_name("runner-request.json"))
PY
}

if [ -n "$prepare_dir" ]; then
  write_runner_request
  printf 'preparedRequest=%s\n' "$prepare_dir/runner-request.json"
  exit 0
fi

if [ "$dry_run" = 1 ]; then
  printf '%s\n' \
    "name=$name" \
    "transport=local" \
    "version=$version" \
    "validationHostVersionJvmOption=-Dturboism.validation.hostVersion=$version" \
    "taskId=$task_id" \
    "runId=$run_id" \
    "bundleRoot=$bundle_root" \
    "agent=$agent" \
    "agentStage=$task_dir/turboism-agent.jar" \
    "homeConfigStage=$home_dir/config.json" \
    "fixtureHost=$fixture_host" \
    "fixtureLocal=$fixture_local" \
    "fixtureName=$fixture_name" \
    "fixtureNameSuffix=$fixture_name_suffix" \
    "fixturePath=$fixture_path" \
    "validationFixtureNameJvmOption=-Dturboism.validation.fixtureName=$fixture_name" \
    "taskDir=$task_dir" \
    "hostRoot=$host_root" \
    "remoteRoot=$host_root" \
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
    "expectedJarSha256=$reviewed_jar_sha256" \
    "resultMarker=$result_marker" \
    "resultFile=$result_file" \
    "trigger=$trigger_path" \
    "cubismJava=$cubism_java" \
    "cubismJavaConsoleMarker=$cubism_java_console_marker" \
    "clientScript=$client_script" \
    "clientScriptTaskName=$client_script_remote_name" \
    "evidenceArchiver=$repo_root/scripts/preview/archive-cubism-host-evidence.sh" \
    "localEvidenceDir=$local_evidence_dir"
  for index in "${!resolved_plugins[@]}"; do printf 'plugin.%s=%s\n' "$index" "${resolved_plugins[$index]}"; done
  for index in "${!resolved_home_files[@]}"; do printf 'homeFile.%s=%s\n' "$index" "${resolved_home_files[$index]}"; done
  for index in "${!resolved_home_dirs[@]}"; do printf 'homeDir.%s=%s\n' "$index" "${resolved_home_dirs[$index]}"; done
  if [ -n "$client_script" ]; then
    printf 'clientScript.sha256=%s\n' "$(sha256_file "$client_script")"
    printf 'clientScript.localPath=%s\n' "$client_script"
  fi
  printf 'turboismAgent.javaToolOption=-javaagent:%s=home=%s;timeoutSeconds=%s\n' \
    "$(z_path "$task_dir/turboism-agent.jar")" "$(z_path "$home_dir")" "$agent_timeout"
  for index in "${!resolved_aux_agents[@]}"; do
    local_path="${resolved_aux_agents[$index]%%:*}"
    remote_name="${resolved_aux_agents[$index]#*:}"
    printf 'auxAgent.%s=%s\n' "$index" "${resolved_aux_agents[$index]}"
    printf 'auxAgent.%s.localPath=%s\n' "$index" "$local_path"
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

if [ -z "$queue_admission_json" ]; then
  # Ordinary direct Runner calls join the same durable queue. Waiting here is
  # only a client concern; its exit never releases or cancels the host slot.
  prepare_dir="$(mktemp -d)"
  trap 'rm -rf -- "$prepare_dir"' EXIT
  write_runner_request
  python3 "$repo_root/scripts/preview/host_validation.py" _enqueue-runner \
    --request "$prepare_dir/runner-request.json"
  exit $?
fi

local_tmp="$(mktemp -d)"
launched=0
evidence_collected=0
success=0
wrapper_cleanup_done=0
pre_cleanup_hook_done=0
background_hook_started=0
process_cleanup_done=0
process_cleanup_status='not-run'
cleanup_status='unknown'
identity_status='UNKNOWN'
identity_expected_sha256="$reviewed_jar_sha256"
identity_actual_sha256=''
fixture_before_sha256=''
fixture_after_sha256=''
source_before_sha256=''
source_after_sha256=''
wrapper_exit=''
normal_exit=0
fixture_unchanged=0
golden_unchanged=0
local_prepare_directory "$task_dir"
local_prepare_directory "$home_dir/plugins"
local_prepare_directory "$home_dir/state"
local_prepare_directory "$home_dir/logs"
local_prepare_directory "$task_dir/agents"
local_prepare_directory "$evidence_dir"
local_assert_copy_safe "$golden_prefix" "$prefix_dir" 1
local_assert_copy_safe "$evidence_dir/." "$local_evidence_dir/" 1

: > "$evidence_dir/owned-pids.tsv"

process_start_time() {
  local pid="$1"
  python3 - "$pid" <<'PY'
from pathlib import Path
import sys

pid = sys.argv[1]
try:
    raw = (Path('/proc') / pid / 'stat').read_bytes()
except (OSError, ValueError):
    raise SystemExit(1)
closing = raw.rfind(b')')
if closing < 0:
    raise SystemExit(1)
fields = raw[closing + 2:].split()
if len(fields) < 20:
    raise SystemExit(1)
print(fields[19].decode('ascii'))
PY
}

record_owned_process_identity() {
  local pid="$1" role="$2" start confirm
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 0
  start="$(process_start_time "$pid" 2>/dev/null || true)"
  if [ -z "$start" ]; then
    printf 'pid=%s role=%s binding=unproven-start-unreadable\n' "$pid" "$role" \
      >> "$evidence_dir/launch-ownership-unproven.txt"
    return 0
  fi
  confirm="$(process_start_time "$pid" 2>/dev/null || true)"
  if [ "$confirm" != "$start" ]; then
    printf 'pid=%s role=%s binding=unproven-start-changed\n' "$pid" "$role" \
      >> "$evidence_dir/launch-ownership-unproven.txt"
    return 0
  fi
  printf '%s\t%s\t%s\n' "$pid" "$start" "$role" >> "$evidence_dir/owned-pids.tsv"
  printf 'pid=%s start=%s role=%s binding=observed-not-atomic\n' \
    "$pid" "$start" "$role" >> "$evidence_dir/launch-ownership-unproven.txt"
}

remote_process_alive() {
  [ ! -s "$evidence_dir/wrapper.exit" ] || return 1
  [ -s "$evidence_dir/wrapper.pid" ] || return 1
  local pid expected actual
  pid="$(cat "$evidence_dir/wrapper.pid" 2>/dev/null || true)"
  expected="$(awk -F '\t' '$3 == "wrapper" { print $2; exit }' "$evidence_dir/owned-pids.tsv" 2>/dev/null || true)"
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
  [ -n "$expected" ] || return 1
  actual="$(process_start_time "$pid" 2>/dev/null || true)"
  [ "$actual" = "$expected" ] || return 1
  kill -0 "$pid" 2>/dev/null
}

remote_normal_exit_evidence_seen() {
  case "$version" in
    5302|5303)
      [ -f "$evidence_dir/cubism-console.txt" ] \
        && grep -Eq -- '-- successfully exited pid:[0-9]+ --' "$evidence_dir/cubism-console.txt"
      ;;
    5203)
      local log_file
      log_file="$(latest_runtime_log)"
      [ -n "$log_file" ] || return 1
      runtime_log_contains "$log_file" 'Stopping Turboism Developer Preview' \
        && runtime_log_contains "$log_file" 'Turboism core shutdown'
      ;;
    *) return 1 ;;
  esac
}

remote_record_wrapper_cleanup() {
  printf '%s\n' 'cubism successful-exit marker observed; task-scoped cleanup invoked' \
    > "$evidence_dir/wrapper.cleanup"
}

remote_stop_process_tree() {
  if [ "$supervisor_cleanup" = 1 ]; then
    # This Runner itself is contained. Only its outside supervisor can prove
    # the whole cgroup empty after we exit; no local process scan may do so.
    process_cleanup_done=1
    process_cleanup_status='deferred'
    cleanup_status='unknown'
    return 0
  fi
  [ "$process_cleanup_done" = 0 ] || return 0
  process_cleanup_done=1
  local candidate pid expected_start comm signal_rc
  local cleanup_uncertain=0 scan_failed=0 selected_identity_uncertain=0
  local observed_task_process=0 wineserver_observed=0
  local processes=()
  local protected_ancestors=()
  declare -A process_start_times=()

  record_uncertain() {
    cleanup_uncertain=1
    printf '%s\n' "$1" >> "$evidence_dir/task-process-cleanup-uncertain.txt"
  }

  is_protected_ancestor() {
    local candidate="$1" ancestor
    [ "$candidate" != "$$" ] || return 0
    for ancestor in "${protected_ancestors[@]}"; do
      [ "$candidate" != "$ancestor" ] || return 0
    done
    return 1
  }

  scan_owned_processes() {
    local proc_root="${1:-/proc}"
    python3 - "$task_dir" "$prefix_dir/pfx" "$proc_root" <<'PY'
from pathlib import Path
import os
import sys

task = sys.argv[1].encode()
prefix = sys.argv[2].encode()
proc_root = Path(sys.argv[3])
self_pid = str(os.getpid())

def fail_scan(message):
    print(f'proc-scan-error: {message}', file=sys.stderr)
    raise SystemExit(2)

def path_token(token, root):
    return token == root or token.startswith(root + b'/')

def start_time(raw):
    closing = raw.rfind(b')')
    if closing < 0:
        return None
    fields = raw[closing + 2:].split()
    return fields[19].decode('ascii') if len(fields) >= 20 else None

try:
    processes = list(proc_root.iterdir())
except OSError as exc:
    fail_scan(f'{proc_root}: {exc}')

for proc in processes:
    if not proc.name.isdigit() or proc.name == self_pid:
        continue
    try:
        raw_cmdline = (proc / 'cmdline').read_bytes()
        raw_environ = (proc / 'environ').read_bytes()
        stat = (proc / 'stat').read_bytes()
        comm = (proc / 'comm').read_bytes().rstrip(b'\n').decode('utf-8', 'replace')
        argv = [token for token in raw_cmdline.split(b'\x00') if token]
        exact_environment = False
        for entry in raw_environ.split(b'\x00'):
            key, separator, value = entry.partition(b'=')
            if not separator:
                continue
            if key == b'TURBOISM_HOST_VALIDATION_TASK_DIR' and value == task:
                exact_environment = True
            if key == b'WINEPREFIX' and value == prefix:
                exact_environment = True
        exact_argument = any(path_token(token, task) or path_token(token, prefix) for token in argv)
        if exact_environment or exact_argument:
            start = start_time(stat)
            if start is None:
                fail_scan(f'{proc}: malformed stat start time')
            print(f'{proc.name}\t{start}\t{comm}')
    except (OSError, UnicodeError, ValueError) as exc:
        fail_scan(f'{proc}: {exc}')
PY
  }

  add_process() {
    local candidate="$1" supplied_start="${2:-}" role="${3:-candidate}" observed_start
    [[ "$candidate" =~ ^[1-9][0-9]*$ ]] || return 0
    if is_protected_ancestor "$candidate"; then
      record_uncertain "protected ancestor candidate pid=$candidate role=$role"
      return 0
    fi
    if [ -z "$supplied_start" ]; then
      record_uncertain "unbound process candidate pid=$candidate role=$role"
      return 0
    fi
    observed_start="$(process_start_time "$candidate" 2>/dev/null || true)"
    if [ -z "$observed_start" ]; then
      if [ -e "/proc/$candidate" ]; then
        record_uncertain "unable to read process identity pid=$candidate role=$role"
      fi
      return 0
    fi
    if [ "$observed_start" != "$supplied_start" ]; then
      printf 'pid-reused pid=%s expectedStart=%s observedStart=%s role=%s\n' \
        "$candidate" "$supplied_start" "$observed_start" "$role" \
        >> "$evidence_dir/task-process-cleanup.pid-reuse"
      selected_identity_uncertain=1
      return 0
    fi
    if [ -n "${process_start_times[$candidate]+x}" ]; then
      if [ "${process_start_times[$candidate]}" != "$observed_start" ]; then
        record_uncertain "pid identity changed pid=$candidate role=$role"
        selected_identity_uncertain=1
      fi
      return 0
    fi
    processes+=("$candidate")
    process_start_times["$candidate"]="$observed_start"
    printf '%s\t%s\t%s\n' "$candidate" "$observed_start" "$role" \
      >> "$evidence_dir/task-process-selected.tsv"
  }

  process_is_same_instance() {
    local candidate="$1" expected="${process_start_times[$1]:-}" observed
    [ -n "$expected" ] || return 1
    [ -e "/proc/$candidate" ] || return 1
    observed="$(process_start_time "$candidate" 2>/dev/null || true)"
    if [ -z "$observed" ]; then
      record_uncertain "unable to re-read process identity pid=$candidate"
      selected_identity_uncertain=1
      return 1
    fi
    if [ "$observed" != "$expected" ]; then
      printf 'pid-reused-before-signal pid=%s expectedStart=%s observedStart=%s\n' \
        "$candidate" "$expected" "$observed" \
        >> "$evidence_dir/task-process-cleanup.pid-reuse"
      selected_identity_uncertain=1
      return 1
    fi
    kill -0 "$candidate" 2>/dev/null
  }

  signal_pidfd() {
    local candidate="$1" expected="$2" signal="$3"
    python3 - "$candidate" "$expected" "$signal" <<'PY'
from pathlib import Path
import os
import signal
import sys

pid = int(sys.argv[1])
expected = sys.argv[2]
signal_name = sys.argv[3]

def start_time(pid_value):
    raw = (Path('/proc') / str(pid_value) / 'stat').read_bytes()
    closing = raw.rfind(b')')
    if closing < 0:
        raise ValueError('malformed stat')
    fields = raw[closing + 2:].split()
    if len(fields) < 20:
        raise ValueError('short stat')
    return fields[19].decode('ascii')

try:
    pidfd_open = getattr(os, 'pidfd_open')
    pidfd_send_signal = getattr(signal, 'pidfd_send_signal')
except AttributeError as exc:
    print(f'pidfd unavailable: {exc}', file=sys.stderr)
    raise SystemExit(10)

try:
    pidfd = pidfd_open(pid)
except OSError as exc:
    print(f'pidfd open failed for {pid}: {exc}', file=sys.stderr)
    raise SystemExit(11)
try:
    observed = start_time(pid)
    if observed != expected:
        print(f'pidfd identity mismatch pid={pid} expected={expected} observed={observed}', file=sys.stderr)
        raise SystemExit(12)
    signal_number = getattr(signal, f'SIG{signal_name}')
    pidfd_send_signal(signal_number, pidfd)
except ProcessLookupError as exc:
    print(f'pidfd target already exited pid={pid}: {exc}', file=sys.stderr)
    raise SystemExit(13)
except OSError as exc:
    print(f'pidfd signal failed for {pid}: {exc}', file=sys.stderr)
    raise SystemExit(14)
finally:
    os.close(pidfd)
PY
  }

  signal_selected() {
    local signal="$1" index candidate expected
    for ((index=${#processes[@]} - 1; index >= 0; index--)); do
      candidate="${processes[$index]}"
      expected="${process_start_times[$candidate]:-}"
      if process_is_same_instance "$candidate"; then
        if signal_pidfd "$candidate" "$expected" "$signal"; then
          :
        else
          signal_rc=$?
          case "$signal_rc" in
            12)
              selected_identity_uncertain=1
              printf 'pidfd identity mismatch before signal pid=%s signal=%s\n' \
                "$candidate" "$signal" >> "$evidence_dir/task-process-cleanup.pid-reuse"
              ;;
            *)
              record_uncertain "pidfd signal unavailable pid=$candidate signal=$signal status=$signal_rc"
              ;;
          esac
        fi
      elif [ -e "/proc/$candidate" ]; then
        record_uncertain "selected process identity could not be proven pid=$candidate signal=$signal"
      fi
    done
  }

  selected_is_alive() {
    local candidate
    for candidate in "${processes[@]}"; do
      if process_is_same_instance "$candidate"; then
        return 0
      fi
    done
    return 1
  }

  : > "$evidence_dir/task-process-selected.tsv"
  : > "$evidence_dir/task-process-cleanup-uncertain.txt"
  : > "$evidence_dir/task-process-cleanup.pid-reuse"
  : > "$evidence_dir/task-process-survivors.txt"

  # Do not walk children with ps --ppid: its parent PID can be reused between
  # lookup and enumeration. Full /proc attribution below is the only candidate
  # source, and every candidate carries its observed start time.
  local cursor="$$" parent
  while [[ "$cursor" =~ ^[1-9][0-9]*$ ]] && [ "$cursor" -ne 1 ]; do
    parent="$(awk '{print $4}' "/proc/$cursor/stat" 2>/dev/null || true)"
    if ! [[ "$parent" =~ ^[1-9][0-9]*$ ]]; then
      record_uncertain "could not verify protected ancestor chain from pid=$cursor"
      break
    fi
    protected_ancestors+=("$parent")
    cursor="$parent"
  done

  local owned_scan="$local_tmp/owned-processes-before-cleanup.tsv"
  if ! scan_owned_processes > "$owned_scan"; then
    record_uncertain 'owned process scan failed before cleanup'
    scan_failed=1
  fi
  if [ "$scan_failed" = 0 ]; then
    while IFS=$'\t' read -r pid expected_start comm; do
      [ -n "$pid" ] || continue
      observed_task_process=1
      add_process "$pid" "$expected_start" owned-scan
      case "$comm" in
        wineserver|wineserver.*) wineserver_observed=1 ;;
      esac
    done < "$owned_scan"
  fi

  if [ "$scan_failed" = 0 ] && [ "${#processes[@]}" -gt 0 ]; then
    signal_selected TERM
    for _ in $(seq 1 10); do
      selected_is_alive || break
      sleep 1
    done
    if selected_is_alive; then
      signal_selected KILL
      for _ in $(seq 1 10); do
        selected_is_alive || break
        sleep 1
      done
    fi
  fi

  local stable_empty=0 scan_pass=0 current_scan found
  while [ "$scan_pass" -lt 3 ] && [ "$scan_failed" = 0 ]; do
    scan_pass=$((scan_pass + 1))
    current_scan="$local_tmp/owned-processes-after-$scan_pass.tsv"
    if ! scan_owned_processes > "$current_scan"; then
      record_uncertain "owned process scan failed after cleanup pass=$scan_pass"
      scan_failed=1
      break
    fi
    found=0
    while IFS=$'\t' read -r pid expected_start comm; do
      [ -n "$pid" ] || continue
      found=1
      observed_task_process=1
      add_process "$pid" "$expected_start" post-cleanup
    done < "$current_scan"
    if [ "$found" = 0 ]; then
      stable_empty=$((stable_empty + 1))
    else
      stable_empty=0
      signal_selected TERM
      sleep 1
      if selected_is_alive; then
        signal_selected KILL
      fi
    fi
  done

  if [ "$scan_failed" = 0 ] && selected_is_alive; then
    record_uncertain 'selected task process remained alive after final scan'
  fi

  # Stable empty scans are only observations. Without a task supervisor/cgroup
  # proof, a detached or late-born child cannot be excluded, so launched tasks
  # never claim safe cleanup from this Runner alone.
  if [ "$launched" = 1 ] || [ "$background_hook_started" = 1 ]; then
    record_uncertain 'late-born or detached task processes cannot be excluded without supervisor proof'
  fi

  {
    printf 'taskDir=%s\n' "$task_dir"
    printf 'trackedProcesses=%s\n' "${#processes[@]}"
    printf 'postCleanupScanPasses=%s\n' "$scan_pass"
    printf 'stableEmptyScans=%s\n' "$stable_empty"
    printf 'wineserverObservedByExactOwnership=%s\n' "$wineserver_observed"
    printf 'automaticSignalMethod=pidfd\n'
    printf 'lateProcessExclusion=unproven-without-supervisor\n'
    printf 'cleanupUncertain=%s\n' "$cleanup_uncertain"
  } > "$evidence_dir/task-process-cleanup.properties"
  if [ "$stable_empty" -lt 2 ] || [ "$cleanup_uncertain" = 1 ] \
    || [ "$selected_identity_uncertain" = 1 ] || [ "$scan_failed" = 1 ] \
    || [ "$observed_task_process" = 1 ] || [ "$launched" = 1 ] \
    || [ "$background_hook_started" = 1 ]; then
    if [ -f "$current_scan" ]; then
      while IFS=$'\t' read -r pid expected_start comm; do
        [ -n "$pid" ] || continue
        printf '%s\t%s\t%s\n' "$pid" "$expected_start" "$comm" \
          >> "$evidence_dir/task-process-survivors.txt"
      done < "$current_scan"
    fi
    process_cleanup_status='unknown'
    return 1
  fi
  rm -f -- "$evidence_dir/task-process-survivors.txt"
  process_cleanup_status='safe'
}

latest_runtime_log() {
  # Runtime logging may not have created its directory on the first poll.
  [ -d "$home_dir/logs/runtime" ] || return 0
  find "$home_dir/logs/runtime" -type f -name '*.log' -printf '%T@ %p\n' 2>/dev/null \
    | sort -nr | head -n 1 | cut -d' ' -f2-
}

runtime_log_contains() {
  local log_file="$1" marker="$2"
  grep -Fq -- "$marker" "$log_file"
}

result_file_contains() {
  local relative="$1" line="$2"
  python3 - "$home_dir/$relative" "$line" <<'PY'
import os
import sys
from pathlib import Path

path = Path(sys.argv[1])
if not path.is_file():
    raise SystemExit(1)
expected = os.fsencode(sys.argv[2])
with path.open('rb') as result:
    for raw in result:
        if raw.removesuffix(b'\n').removesuffix(b'\r') == expected:
            raise SystemExit(0)
raise SystemExit(1)
PY
}

verify_staged_artifacts() {
  local phase="$1" actual expected spec local_path remote_name
  expected="$(sha256_file "$agent")"
  actual="$(sha256_file "$task_dir/turboism-agent.jar")"
  [ "$actual" = "$expected" ] || fail "staged agent hash mismatch"
  for spec in "${resolved_plugins[@]}"; do
    local_path="${spec%%:*}"
    remote_name="${spec#*:}"
    expected="$(sha256_file "$local_path")"
    actual="$(sha256_file "$home_dir/plugins/$remote_name")"
    [ "$actual" = "$expected" ] || fail "staged plugin hash mismatch: $remote_name"
  done
  for spec in "${resolved_home_files[@]}"; do
    local_path="${spec%%:*}"
    relative_path="${spec#*:}"
    expected="$(sha256_file "$local_path")"
    actual="$(sha256_file "$home_dir/$relative_path")"
    [ "$actual" = "$expected" ] || fail "staged home-file hash mismatch: $relative_path"
  done
  for spec in "${resolved_home_dirs[@]}"; do
    local_path="${spec%%:*}"
    relative_path="${spec#*:}"
    local_hash="$(tar -C "$local_path" --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner -cf - . | sha256sum | cut -d' ' -f1)"
    staged_hash="$(tar -C "$home_dir/$relative_path" --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner -cf - . | sha256sum | cut -d' ' -f1)"
    [ "$staged_hash" = "$local_hash" ] || fail "staged home-dir hash mismatch: $relative_path"
  done
  if [ "${#resolved_aux_agents[@]}" -gt 0 ]; then
    local aux_hash_file="$local_tmp/aux-agent-hashes-$phase.properties"
    : > "$aux_hash_file"
    for index in "${!resolved_aux_agents[@]}"; do
      local_path="${resolved_aux_agents[$index]%%:*}"
      remote_name="${resolved_aux_agents[$index]#*:}"
      expected="$(sha256_file "$local_path")"
      actual="$(sha256_file "$task_dir/agents/$remote_name")"
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
  local runtime_log archive_script
  local_prepare_directory "$evidence_dir"
  runtime_log="$(latest_runtime_log || true)"
  [ -z "$runtime_log" ] || cp -- "$runtime_log" "$evidence_dir/turboism.log" 2>/dev/null || true
  [ ! -f "$home_dir/state/plugin-load-report.json" ] \
    || cp -- "$home_dir/state/plugin-load-report.json" "$evidence_dir/" || true
  [ ! -f "$home_dir/state/preview-runtime-report.json" ] \
    || cp -- "$home_dir/state/preview-runtime-report.json" "$evidence_dir/" || true
  if [ -n "$result_file" ] && [ -f "$home_dir/$result_file" ]; then
    local_prepare_directory "$evidence_dir/result"
    cp -- "$home_dir/$result_file" "$evidence_dir/result/$(basename "$result_file")" || true
  fi
  archive_script="$task_dir/archive-cubism-host-evidence.sh"
  [ ! -x "$archive_script" ] || "$archive_script" "$home_dir" "$evidence_dir" 2>/dev/null || true
  {
    printf 'fixture_after_sha256=%s\n' "$(sha256sum "$fixture_path" 2>/dev/null | cut -d' ' -f1 || true)"
    if [ -n "$fixture_host" ]; then
      printf 'source_fixture_after_sha256=%s\n' "$(sha256sum "$fixture_host" 2>/dev/null | cut -d' ' -f1 || true)"
    elif [ -n "$fixture_local" ]; then
      printf 'source_fixture_after_sha256=%s\n' "$(sha256sum "$fixture_local" 2>/dev/null | cut -d' ' -f1 || true)"
    fi
    printf 'golden_jar_after_sha256=%s\n' "$(sha256sum "$golden_cubism/app/lib/Live2D_Cubism.jar" 2>/dev/null | cut -d' ' -f1 || true)"
    printf 'golden_bat_after_sha256=%s\n' "$(sha256sum "$golden_cubism/CubismEditor5.bat" 2>/dev/null | cut -d' ' -f1 || true)"
    printf 'cloned_jar_after_sha256=%s\n' "$(sha256sum "$cloned_cubism/app/lib/Live2D_Cubism.jar" 2>/dev/null | cut -d' ' -f1 || true)"
    printf 'cloned_bat_after_sha256=%s\n' "$(sha256sum "$cloned_cubism/CubismEditor5.bat" 2>/dev/null | cut -d' ' -f1 || true)"
    printf 'golden_unchanged=%s\n' "$golden_unchanged"
    printf 'wrapper_exit=%s\n' "$(cat "$evidence_dir/wrapper.exit" 2>/dev/null || true)"
  } > "$evidence_dir/final-hashes.properties"
  local_prepare_directory "$local_evidence_dir"
  local_copy_from --recursive "$evidence_dir/." "$local_evidence_dir/"
}

cleanup_prefix() {
  [ "$supervisor_cleanup" = 0 ] || return 0
  [ "$keep_prefix" = 0 ] || return 0
  local_remove_tree "$prefix_dir"
}

run_remote_hook() {
  local hook="$1"
  [ -n "$hook" ] || return 0
  if [ "$hook" = "$remote_pre_cleanup" ] && [ "$pre_cleanup_hook_done" = 1 ]; then
    return 0
  fi
  local task_hook="$task_dir/$(basename "$hook")"
  local_copy_to "$hook" "$task_hook"
  chmod 700 -- "$task_hook"
  local hook_arg expanded_hook_arg hook_log hook_home_win hook_fixture_win
  hook_home_win="$(z_path "$home_dir")"
  hook_fixture_win="$(z_path "$fixture_path")"
  local hook_context=(
    "$task_dir" "$home_dir" "$evidence_dir" "$prefix_dir" "$fixture_path" "$task_id"
    "$version" "$result_timeout" "$proton_wrapper" "$proton_runner" "$display"
  )
  local expanded_hook_args=()
  for hook_arg in "${remote_pre_launch_args[@]}"; do
    expanded_hook_arg="${hook_arg//\{TASK_ID\}/$task_id}"
    expanded_hook_arg="${expanded_hook_arg//\{HOME\}/$hook_home_win}"
    expanded_hook_arg="${expanded_hook_arg//\{FIXTURE\}/$hook_fixture_win}"
    expanded_hook_arg="${expanded_hook_arg//\{FIXTURE_NAME\}/$fixture_name}"
    expanded_hook_args+=("$expanded_hook_arg")
  done
  hook_log="$(safe_label "$(basename "$hook")")"
  if [ "$hook" = "$remote_pre_launch" ] && [ "$remote_pre_launch_background" = 1 ]; then
    if [ "$remote_pre_launch_args_only" = 1 ]; then
      TURBOISM_HOST_VALIDATION_TASK_DIR="$task_dir" "$task_hook" "${expanded_hook_args[@]}" \
        > "$evidence_dir/$hook_log.out" 2> "$evidence_dir/$hook_log.err" &
    else
      TURBOISM_HOST_VALIDATION_TASK_DIR="$task_dir" "$task_hook" "${hook_context[@]}" "${expanded_hook_args[@]}" \
        > "$evidence_dir/$hook_log.out" 2> "$evidence_dir/$hook_log.err" &
    fi
    background_hook_started=1
    printf '%s\n' "$!" > "$evidence_dir/background-hook.pid"
    record_owned_process_identity "$!" background-hook
  elif [ "$remote_pre_launch_args_only" = 1 ] && [ "$hook" = "$remote_pre_launch" ]; then
    TURBOISM_HOST_VALIDATION_TASK_DIR="$task_dir" "$task_hook" "${expanded_hook_args[@]}"
  else
    TURBOISM_HOST_VALIDATION_TASK_DIR="$task_dir" "$task_hook" "${hook_context[@]}" "${expanded_hook_args[@]}"
  fi
  if [ "$hook" = "$remote_pre_cleanup" ]; then
    pre_cleanup_hook_done=1
  fi
}

write_lifecycle_result() {
  local rc="$1" cleanup_rc="$2" result_status='FAIL' cleanup_value="$cleanup_status"
  local lifecycle_tmp="$evidence_dir/.lifecycle-result.json.$$"
  if [ "$success" = 1 ] && [ "$rc" -eq 0 ] && [ "$cleanup_rc" -eq 0 ] \
    && [ "$identity_status" = PASS ] && [ "$fixture_unchanged" = 1 ] \
    && [ "$normal_exit" = 1 ] && [ "$cleanup_status" = safe ] \
    && [ "$golden_unchanged" = 1 ]; then
    result_status='PASS'
  fi
  [ -n "$cleanup_value" ] || cleanup_value=unknown
  if [ "$result_status" != PASS ] && {
    [ "$cleanup_value" = unknown ] || [ "$identity_status" = UNKNOWN ] || [ "$normal_exit" = 0 ]
  }; then
    result_status='UNKNOWN'
  fi
  TURBOISM_LOCAL_EVIDENCE_DIR="$local_evidence_dir" \
  python3 - "$lifecycle_tmp" "$task_id" "$result_status" "$rc" "$cleanup_value" \
    "$identity_status" "$fixture_unchanged" "$normal_exit" "$identity_expected_sha256" \
    "$identity_actual_sha256" "$fixture_before_sha256" "$fixture_after_sha256" \
    "$source_before_sha256" "$source_after_sha256" "$golden_unchanged" "$wrapper_exit" \
    "$task_dir" "$evidence_dir" "$keep_prefix" "$queue_admission_json" "$success" \
    "${golden_bat_before:-}" "${cloned_bat_before:-}" <<'PY'
import json
import os
import sys
from pathlib import Path

(
    output, run_id, status, exit_code, cleanup, identity_status, fixture_unchanged,
    normal_exit, expected_jar, actual_jar, fixture_before, fixture_after,
    source_before, source_after, golden_unchanged, wrapper_exit, task_dir, evidence_dir, keep_prefix, admission_json,
    validation_complete, golden_bat_before, cloned_bat_before,
) = sys.argv[1:]
admission = json.loads(admission_json)
if admission.get("schemaVersion") != 1 or admission.get("runId") != run_id:
    raise SystemExit("lifecycle admission identity mismatch")
payload = {
    "schemaVersion": 1,
    "jobId": admission["jobId"],
    "attemptId": admission["attemptId"],
    "runId": run_id,
    "preparedDigest": admission["preparedDigest"],
    "cleanup": cleanup,
    "validationStatus": status,
    "identityVerified": identity_status == "PASS",
    "fixtureUnchanged": fixture_unchanged == "1",
    "normalExit": normal_exit == "1",
    "details": {
        "exitCode": int(exit_code),
        "identityStatus": identity_status,
        "identityExpectedJarSha256": expected_jar,
        "identityActualJarSha256": actual_jar or None,
        "fixtureBeforeSha256": fixture_before or None,
        "fixtureAfterSha256": fixture_after or None,
        "sourceFixtureBeforeSha256": source_before or None,
        "sourceFixtureAfterSha256": source_after or None,
        "goldenUnchanged": golden_unchanged == "1",
        "wrapperExit": int(wrapper_exit) if wrapper_exit.isdigit() else None,
        "taskDir": task_dir,
        "evidenceDir": evidence_dir,
        "prefixRetained": (Path(task_dir) / "prefix").exists(),
        "cleanupOwner": admission.get("cleanupOwner"),
        "validationComplete": validation_complete == "1",
        "goldenBatBeforeSha256": golden_bat_before or None,
        "clonedBatBeforeSha256": cloned_bat_before or None,
        "taskOwnedCleanup": cleanup == "safe",
    },
}
target = Path(output)
with target.open('w', encoding='utf-8') as stream:
    stream.write(json.dumps(payload, ensure_ascii=False, indent=2) + '\n')
    stream.flush()
    os.fsync(stream.fileno())
os.replace(target, Path(evidence_dir) / "lifecycle-result.json")
local_evidence = Path(os.environ["TURBOISM_LOCAL_EVIDENCE_DIR"])
local_evidence.mkdir(parents=True, exist_ok=True)
copy_tmp = local_evidence / f'.lifecycle-result.json.{os.getpid()}'
with copy_tmp.open('xb') as stream:
    stream.write((Path(evidence_dir) / 'lifecycle-result.json').read_bytes())
    stream.flush()
    os.fsync(stream.fileno())
os.replace(copy_tmp, local_evidence / 'lifecycle-result.json')
for directory in (Path(evidence_dir), local_evidence):
    descriptor = os.open(directory, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
PY
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
  if [ "$background_hook_started" = 1 ] && [ "$process_cleanup_done" = 0 ]; then
    remote_stop_process_tree || cleanup_rc=1
  fi
  collect_evidence || cleanup_rc=1
  if [ "$success" = 1 ] && [ "$cleanup_rc" -eq 0 ]; then
    cleanup_prefix || cleanup_rc=1
  fi
  if [ "$cleanup_rc" -ne 0 ]; then
    cleanup_status='unknown'
    printf 'host validation: TASK CLEANUP FAILED task=%s host=%s evidence=%s\n' \
      "$task_id" "$task_dir" "$local_evidence_dir" >&2
    [ "$rc" -ne 0 ] || rc=1
  elif [ "$process_cleanup_status" = safe ] \
    || { [ "$launched" = 0 ] && [ "$background_hook_started" = 0 ] \
      && [ "$process_cleanup_done" = 0 ]; }; then
    cleanup_status='safe'
  fi
  if [ "$rc" -ne 0 ]; then
    printf 'host validation: FAILED task=%s host=%s evidence=%s\n' \
      "$task_id" "$task_dir" "$local_evidence_dir" >&2
  fi
  write_lifecycle_result "$rc" "$cleanup_rc"
  rm -rf -- "$local_tmp"
  exit "$rc"
}
trap on_exit EXIT

log "preflight exact host identity (transport=local)"
identity_before="$evidence_dir/identity-before.properties"
fixture_source="$fixture_host"
[ -n "$fixture_source" ] || fixture_source="$fixture_local"
source_before_sha256="$(sha256_file "$fixture_source")"
if [ -n "$fixture_sha256" ] && [ "$source_before_sha256" != "$fixture_sha256" ]; then
  fail "fixture hash mismatch: expected $fixture_sha256 got $source_before_sha256"
fi
golden_jar_before=''
golden_bat_before=''
cloned_jar_before=''
cloned_bat_before=''
jar="$golden_cubism/app/lib/Live2D_Cubism.jar"
bat="$golden_cubism/CubismEditor5.bat"
[ -f "$jar" ] || fail "exact host identity missing JAR: $jar"
[ -f "$bat" ] || fail "exact host identity missing official BAT: $bat"
identity_actual_sha256="$(sha256_file "$jar")"
[ "$identity_actual_sha256" = "$reviewed_jar_sha256" ] \
  || fail "exact host identity JAR hash mismatch: expected $reviewed_jar_sha256 got $identity_actual_sha256"
if pgrep -af 'CubismEditor5|CECubismEditorApp|wineserver' 2>/dev/null \
  | grep -F -- "$golden_prefix" >/dev/null; then
  fail "golden Proton prefix is already in use: $golden_prefix"
fi
golden_jar_before="$identity_actual_sha256"
golden_bat_before="$(sha256_file "$bat")"
{
  printf 'schemaVersion=1\n'
  printf 'taskId=%s\n' "$task_id"
  printf 'runId=%s\n' "$run_id"
  printf 'goldenPrefix=%s\n' "$golden_prefix"
  printf 'hostJarSize=%s\n' "$(stat -c %s "$jar")"
  printf 'hostJarSha256=%s\n' "$identity_actual_sha256"
  printf 'officialBatSha256=%s\n' "$golden_bat_before"
  printf 'sourceFixtureSha256=%s\n' "$source_before_sha256"
  printf 'identity=PASS\n'
} > "$identity_before"
identity_status='PASS'

log "creating task directory and CoW prefix clone"
cp -a --reflink=always -- "$golden_prefix" "$prefix_dir"
rm -f -- "$prefix_dir/pfx.lock"
[ -d "$prefix_dir/pfx/drive_c/windows" ] || fail "cloned Proton prefix is incomplete"
cloned_cubism="$prefix_dir/$cubism_rel"
cloned_jar_before="$(sha256_file "$cloned_cubism/app/lib/Live2D_Cubism.jar")"
cloned_bat_before="$(sha256_file "$cloned_cubism/CubismEditor5.bat")"
[ "$cloned_jar_before" = "$reviewed_jar_sha256" ] || fail "cloned Cubism JAR hash mismatch"
{
  printf 'clonedJarSha256=%s\n' "$cloned_jar_before"
  printf 'clonedBatSha256=%s\n' "$cloned_bat_before"
} > "$evidence_dir/cloned-identity.properties"
local_copy_to "$repo_root/scripts/preview/archive-cubism-host-evidence.sh" "$task_dir/archive-cubism-host-evidence.sh"
chmod 700 -- "$task_dir/archive-cubism-host-evidence.sh"
local_copy_to "$agent" "$task_dir/turboism-agent.jar"
if [ -n "$home_config" ]; then
  local_copy_to "$home_config" "$home_dir/config.json"
  staged_config_sha="$(sha256_file "$home_dir/config.json")"
  [ -s "$home_dir/config.json" ] || fail "staged home config is empty"
  [ "$staged_config_sha" = "$(sha256_file "$home_config")" ] || fail "staged home config hash mismatch"
fi
for spec in "${resolved_plugins[@]}"; do
  local_path="${spec%%:*}"
  remote_name="${spec#*:}"
  local_copy_to "$local_path" "$home_dir/plugins/$remote_name"
done
for spec in "${resolved_home_files[@]}"; do
  local_path="${spec%%:*}"
  relative_path="${spec#*:}"
  local_prepare_directory "$home_dir/$(dirname "$relative_path")"
  local_copy_to "$local_path" "$home_dir/$relative_path"
done
for spec in "${resolved_home_dirs[@]}"; do
  local_path="${spec%%:*}"
  relative_path="${spec#*:}"
  local_prepare_directory "$home_dir/$relative_path"
  local_copy_dir_contents_to "$local_path" "$home_dir/$relative_path"
done
for spec in "${resolved_aux_agents[@]}"; do
  local_path="${spec%%:*}"
  remote_name="${spec#*:}"
  local_copy_to "$local_path" "$task_dir/agents/$remote_name"
done
if [ -n "$client_script" ]; then
  local_copy_to "$client_script" "$task_dir/$client_script_remote_name"
  client_sha256="$(sha256_file "$client_script")"
  [ "$client_sha256" = "$(sha256_file "$task_dir/$client_script_remote_name")" ] \
    || fail "staged client script hash mismatch"
  chmod 700 -- "$task_dir/$client_script_remote_name"
fi
verify_staged_artifacts before
if [ "${#resolved_aux_agents[@]}" -gt 0 ]; then
  cat "$local_tmp/aux-agent-hashes-before.properties" >> "$identity_before"
  local_copy_to "$local_tmp/aux-agent-hashes-before.properties" \
    "$evidence_dir/aux-agent-hashes-before.properties"
  # identity_before already names the task-local evidence file.
fi
local_copy_host_file "$fixture_source" "$fixture_path"
fixture_before_sha256="$(sha256_file "$fixture_path")"
[ "$fixture_before_sha256" = "$source_before_sha256" ] || fail "copied fixture hash differs from source"
printf 'fixtureBeforeSha256=%s\n' "$fixture_before_sha256" > "$local_tmp/fixture-before.properties"
local_copy_to "$local_tmp/fixture-before.properties" "$evidence_dir/fixture-before.properties"

win_home="$(z_path "$home_dir")"
win_agent="$(z_path "$task_dir/turboism-agent.jar")"
win_fixture="$(z_path "$fixture_path")"
win_console="$(z_path "$evidence_dir/cubism-console.txt")"
win_launch="$(z_path "$task_dir/launch.bat")"
cmd_unix="$prefix_dir/pfx/drive_c/windows/system32/cmd.exe"

if [ -n "$cubism_java" ]; then
  remote_args_bash "$cloned_cubism/CubismEditor5.bat" \
    "$cloned_cubism/CubismEditor5-java-override.bat" "$cubism_java" <<'LOCAL'
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
LOCAL
  remote_args_bash "$cloned_cubism/CubismEditor5-java-override.bat" "$cubism_java" \
    "$evidence_dir/cubism-java.properties" <<'LOCAL'
set -euo pipefail
remote_args
launcher="${REMOTE_ARGS[0]}"; java_exe="${REMOTE_ARGS[1]}"; evidence="${REMOTE_ARGS[2]}"
grep -Fq -- "set JAVA_EXE=$java_exe" "$launcher"
{
  printf 'configuredWindowsPath=%s\n' "$java_exe"
  printf 'overrideLauncherSha256=%s\n' "$(sha256_file "$launcher")"
} > "$evidence"
LOCAL
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
  win_task_launcher="$cubism_win\\CubismEditor5-java-override.bat"
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
local_copy_to "$local_tmp/launch.bat" "$task_dir/launch.bat"
cat > "$local_tmp/launch.sh" <<SH
#!/bin/sh
set -u
export DISPLAY="$display"
export TURBOISM_HOST_VALIDATION_TASK_DIR="$task_dir"
cd "$task_dir" || exit 1
"$proton_wrapper" -p "$prefix_dir" --runner "$proton_runner" --debug "$cmd_unix" /c "$win_launch" > "$evidence_dir/launcher.out" 2>&1
rc=\$?
printf '%s\\n' "\$rc" > "$evidence_dir/wrapper.exit"
exit "\$rc"
SH
local_copy_to "$local_tmp/launch.sh" "$task_dir/launch.sh"
chmod 700 -- "$task_dir/launch.sh"

log "launching exact Cubism $version through official BAT"
(
  cd "$task_dir" || exit 1
  nohup ./launch.sh </dev/null >/dev/null 2>&1 &
  printf '%s\n' "$!" > "$evidence_dir/wrapper.pid"
  record_owned_process_identity "$(cat "$evidence_dir/wrapper.pid")" wrapper
)
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
  local_prepare_directory "$home_dir/$(dirname "$trigger_path")"
  touch -- "$home_dir/$trigger_path"
fi
if [ -n "$client_script" ]; then
  log "running task-local validation client $client_script_remote_name"
  if ! "$task_dir/$client_script_remote_name" "$home_dir" "$task_id" \
    > "$evidence_dir/client.out" 2> "$evidence_dir/client.err"; then
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
    case "$version" in 5302|5303) normal_exit=1 ;; esac
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
  remote_record_wrapper_cleanup
  remote_stop_process_tree
  wrapper_cleanup_done=1
fi
wrapper_exit="$(cat "$evidence_dir/wrapper.exit" 2>/dev/null || true)"
if [ "$wrapper_cleanup_done" = 0 ]; then
  [ -n "$wrapper_exit" ] || fail "official launcher exited with code missing"
  [ "$wrapper_exit" = 0 ] || fail "official launcher exited with code $wrapper_exit"
  normal_exit=1
elif [ -n "$wrapper_exit" ] && [ "$wrapper_exit" != 0 ] && [ "$wrapper_exit" != 1 ]; then
  fail "official launcher cleanup exited with unexpected code $wrapper_exit"
fi
if [ -n "$cubism_java_console_marker" ]; then
  grep -Fq -- "$cubism_java_console_marker" "$evidence_dir/cubism-console.txt" \
    || fail "Cubism Java identity marker was not observed: $cubism_java_console_marker"
fi
verify_staged_artifacts after
if [ "${#resolved_aux_agents[@]}" -gt 0 ]; then
  local_copy_to "$local_tmp/aux-agent-hashes-after.properties" \
    "$evidence_dir/aux-agent-hashes-after.properties"
fi
source_after_sha256="$(sha256_file "$fixture_source")"
[ "$source_after_sha256" = "$source_before_sha256" ] || fail "source fixture changed"
fixture_after_sha256="$(sha256_file "$fixture_path")"
if [ "$require_fixture_unchanged" = 1 ] && [ "$fixture_after_sha256" != "$fixture_before_sha256" ]; then
  fail "copied fixture changed despite --require-fixture-unchanged"
fi
[ "$fixture_after_sha256" = "$fixture_before_sha256" ] || fail "copied fixture changed"
fixture_unchanged=1
golden_jar_after="$(sha256_file "$golden_cubism/app/lib/Live2D_Cubism.jar")"
cloned_jar_after="$(sha256_file "$cloned_cubism/app/lib/Live2D_Cubism.jar")"
golden_bat_after="$(sha256_file "$golden_cubism/CubismEditor5.bat")"
cloned_bat_after="$(sha256_file "$cloned_cubism/CubismEditor5.bat")"
[ "$golden_jar_after" = "$reviewed_jar_sha256" ] || fail "golden Cubism JAR changed"
[ "$cloned_jar_after" = "$reviewed_jar_sha256" ] || fail "cloned Cubism JAR changed"
[ "$golden_bat_after" = "$golden_bat_before" ] || fail "golden Cubism launcher changed"
[ "$cloned_bat_after" = "$cloned_bat_before" ] || fail "cloned Cubism launcher changed"
golden_unchanged=1
run_remote_hook "$remote_pre_cleanup"
remote_stop_process_tree || fail "task-owned process cleanup could not be proven safe"
collect_evidence
success=1

if [ "$supervisor_cleanup" = 1 ]; then
  log "validation phase complete task=$task_id; final cleanup verdict pending supervisor"
else
  log "PASS task=$task_id"
fi
log "local task=$task_dir"
log "local evidence=$local_evidence_dir"
