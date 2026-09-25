#!/usr/bin/env bash
# Offline security contract for run-cubism-host-validation.sh.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/../.." && pwd -P)"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
hook_invocations=$(grep -c 'DISPLAY="$display" TURBOISM_HOST_VALIDATION_TASK_DIR="$task_dir" "$task_hook"' "$runner")
if [ "$hook_invocations" -lt 3 ]; then
  fail "Runner hook invocations must inherit the managed DISPLAY (found $hook_invocations of 3)"
fi
if grep -n 'TURBOISM_HOST_VALIDATION_TASK_DIR="$task_dir" "$task_hook"' "$runner" | grep -qv 'DISPLAY='; then
  fail "Runner launches a hook without the managed DISPLAY"
fi

tmp="$(mktemp -d "${TMPDIR:-/tmp}/turboism-cubism-args.XXXXXX")"
cleanup() { rm -rf -- "$tmp"; }
trap cleanup EXIT

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }
expect_rejected() {
  local label="$1" needle="$2"
  shift 2
  if "$@" >"$tmp/$label.out" 2>&1; then
    fail "$label unexpectedly succeeded"
  fi
  grep -Fq -- "$needle" "$tmp/$label.out" || {
    printf -- '--- %s output ---\n' "$label" >&2
    cat "$tmp/$label.out" >&2
    fail "$label did not report $needle"
  }
}

bundle="$tmp/bundle"
mkdir -p "$bundle"
printf 'agent\n' > "$bundle/agent.jar"
printf 'plugin\n' > "$bundle/probe.jar"
printf 'fixture\n' > "$tmp/fixture.cmo3"
printf 'home-file\n' > "$tmp/home-file.txt"
mkdir -p "$tmp/home-dir"
printf 'home-dir\n' > "$tmp/home-dir/value.txt"

host_args=(--golden-prefix "$tmp/golden" --host-root "$tmp/host"
  --proton-runner "$tmp/proton")
base=(bash "$runner" --name arg-contract --version 5302 --bundle-root "$bundle"
  --agent "$bundle/agent.jar" --plugin "$bundle/probe.jar" --fixture-host "$tmp/fixture.cmo3"
  --result-file state/result.txt "${host_args[@]}" --dry-run)

# Legacy remote mode is rejected even when its former connection inputs exist.
expect_rejected remote-mode 'local-only' "${base[@]}" --transport remote
cubism_java='Z:\home\local-user\TurboismValidation\tools\graalvm-25.2.4\bin\java.exe'
"${base[@]}" --home-file "$tmp/home-file.txt:scripts/input.txt" --home-dir "$tmp/home-dir:scripts" \
  --trigger state/trigger.flag --windows-env 'HOME={HOME}\\\\fx-home' \
  --windows-env 'USERPROFILE={HOME}\\\\fx-home' --cubism-java "$cubism_java" \
  --cubism-java-console-marker 'GraalVM Community' > "$tmp/good.out"
grep -Fq 'homeFileCount=1' "$tmp/good.out" || fail 'valid home-file was not accepted'
grep -Fq 'homeDirCount=1' "$tmp/good.out" || fail 'valid home-dir was not accepted'
grep -Fq 'trigger=state/trigger.flag' "$tmp/good.out" || fail 'valid trigger was not accepted'
grep -Fq 'windowsEnvironmentCount=2' "$tmp/good.out" \
  || fail 'valid Windows environment assignments were not accepted'
grep -Fq 'windowsEnvironment.0=HOME=Z:' "$tmp/good.out" \
  || fail 'HOME Windows environment placeholder was not expanded'
grep -Fq 'windowsEnvironment.1=USERPROFILE=Z:' "$tmp/good.out" \
  || fail 'USERPROFILE Windows environment placeholder was not expanded'
grep -Fq "cubismJava=$cubism_java" "$tmp/good.out" || fail 'valid Cubism Java override was not accepted'
grep -Fq 'cubismJavaConsoleMarker=GraalVM Community' "$tmp/good.out" \
  || fail 'Cubism Java console marker was not accepted'
grep -Fq 'transport=local' "$tmp/good.out" || fail 'local transport was not selected'

# Keep both spellings covered while the local aliases replace SSH placement.
legacy=(bash "$runner" --name arg-contract --version 5302 --bundle-root "$bundle"
  --agent "$bundle/agent.jar" --plugin "$bundle/probe.jar" --fixture-remote "$tmp/fixture.cmo3"
  --result-file state/result.txt --golden-prefix "$tmp/golden" --remote-root "$tmp/legacy-host"
  --proton-runner "$tmp/proton" --dry-run)
"${legacy[@]}" > "$tmp/legacy.out"
grep -Fq 'transport=local' "$tmp/legacy.out" || fail 'legacy local aliases were not accepted'

"${base[@]}" \
  --result-pass-line '{"type":"summary","status":"PASS"}' \
  --result-fail-line '{"type":"summary","status":"FAIL"}' \
  > "$tmp/good-json-marker.out"
expect_rejected marker-control 'marker contains an unsupported control character' \
  "${base[@]}" --result-pass-line $'status=PASS\nextra'

auth=(bash "$runner" --name arg-contract --version 5303 --bundle-root "$bundle"
  --agent "$bundle/agent.jar" --plugin "$bundle/probe.jar" --fixture-local "$tmp/fixture.cmo3"
  --result-file state/result.txt "${host_args[@]}" --dry-run)
"${auth[@]}" > "$tmp/good-5303.out"
grep -Fq 'version=5303' "$tmp/good-5303.out" || fail 'exact 5.3.03 version was not accepted'
grep -Fq 'expectedJarSha256=bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166' \
  "$tmp/good-5303.out" || fail 'exact 5.3.03 reviewed artifact was not pinned'
grep -Fq 'Program Files/Live2D Cubism 5.3.03' "$tmp/good-5303.out" \
  || fail 'exact 5.3.03 installation path was not selected'

expect_rejected result-traversal 'result file must be a normalized relative Unix path' \
  "${base[@]}" --result-file '../outside'
expect_rejected result-metachar 'result file must contain only ASCII' \
  "${base[@]}" --result-file 'state/$(touch-pwned)'
expect_rejected trigger-metachar 'trigger path must contain only ASCII' \
  "${base[@]}" --trigger 'state/trigger;touch-pwned'
expect_rejected home-file-traversal 'home-file destination must be a normalized relative Unix path' \
  "${base[@]}" --home-file "$tmp/home-file.txt:../outside"
expect_rejected home-dir-metachar 'home-dir destination must contain only ASCII' \
  "${base[@]}" --home-dir "$tmp/home-dir:scripts/\$(touch-pwned)"
expect_rejected control-character 'result file must contain only ASCII' \
  "${base[@]}" --result-file $'state/result\n.txt'
expect_rejected ssh-host-migration 'no longer supported in the local-only Runner' \
  "${base[@]}" --ssh-host 'operator@example.invalid'
expect_rejected ssh-key-migration 'no longer supported in the local-only Runner' \
  "${base[@]}" --ssh-key "$tmp/not-a-secret-key"
expect_rejected windows-env-format 'Windows environment assignment must use NAME=value' \
  "${base[@]}" --windows-env 'HOME'
expect_rejected windows-env-duplicate 'duplicate Windows environment name' \
  "${base[@]}" --windows-env 'HOME=first' --windows-env 'HOME=second'
expect_rejected windows-env-java 'Windows environment assignment may not override JAVA_TOOL_OPTIONS' \
  "${base[@]}" --windows-env 'JAVA_TOOL_OPTIONS=-javaagent:pwned.jar'
expect_rejected windows-env-java-case 'Windows environment assignment may not override _java_options' \
  "${base[@]}" --windows-env '_java_options=-javaagent:pwned.jar'
expect_rejected windows-env-duplicate-case 'duplicate Windows environment name' \
  "${base[@]}" --windows-env 'Path=first' --windows-env 'PATH=second'
expect_rejected windows-env-command 'Windows environment value contains an unsupported command character' \
  "${base[@]}" --windows-env 'HOME=C:\safe&whoami'
expect_rejected jvm-option-quote 'JVM or hook option contains an unsupported quote' \
  "${base[@]}" --jvm-option '-Dunsafe="quoted"'

# Job-local Linux environment admits only the reviewed Mesa debug names.
"${base[@]}" --linux-env 'mesa_glthread=true' --linux-env 'MESA_DEBUG=1' \
  --linux-env 'GALLIUM_HUD_PERIOD=0.5' > "$tmp/linux-env.out"
grep -Fq 'linuxEnvironmentCount=4' "$tmp/linux-env.out" \
  || fail 'admitted Linux environment assignments were not accepted'
grep -Fq 'linuxEnvironment.0=mesa_glthread=true' "$tmp/linux-env.out" \
  || fail 'mesa_glthread assignment was not preserved verbatim'
grep -Fq 'linuxEnvironment.2=GALLIUM_HUD_PERIOD=0.5' "$tmp/linux-env.out" \
  || fail 'GALLIUM_HUD_PERIOD assignment was not preserved verbatim'
grep -Fq 'linuxEnvironment.3=TURBOISM_PROTON=1' "$tmp/linux-env.out" \
  || fail 'the managed Proton marker must always be exported on this path'

# Proton-scoped product options default ON here: no staged config still
# exports mesa_glthread and injects both properties.
"${base[@]}" > "$tmp/proton-defaults.out"
grep -Fq 'mesaGlThread=1' "$tmp/proton-defaults.out" \
  || fail 'absent launcher.mesaGlThread must default on under Proton'
grep -Fq 'inputPathElision=1' "$tmp/proton-defaults.out" \
  || fail 'absent launcher.inputPathElision must default on under Proton'
grep -Fq 'TURBOISM_PROTON=1' "$tmp/proton-defaults.out" \
  || fail 'managed Proton marker missing from the default export set'
grep -Fq 'mesa_glthread=true' "$tmp/proton-defaults.out" \
  || fail 'default-on mesaGlThread must export mesa_glthread=true'

# Explicit config false always wins over the Proton default.
printf '{"launcher":{"mesaGlThread":false,"inputPathElision":false}}\n' \
  > "$tmp/off-config.json"
"${base[@]}" --home-config "$tmp/off-config.json" > "$tmp/proton-off.out"
grep -Fq 'mesaGlThread=0' "$tmp/proton-off.out" \
  || fail 'explicit launcher.mesaGlThread=false must disable the option'
grep -Fq 'inputPathElision=0' "$tmp/proton-off.out" \
  || fail 'explicit launcher.inputPathElision=false must disable the option'
if grep -Fq 'linuxEnvironment' "$tmp/proton-off.out" \
   && grep -Fq 'mesa_glthread=true' "$tmp/proton-off.out"; then
  fail 'explicit opt-out must not export mesa_glthread=true'
fi

# Explicit config true keeps both halves enabled.
printf '{"launcher":{"mesaGlThread":true,"inputPathElision":true}}\n' \
  > "$tmp/on-config.json"
"${base[@]}" --home-config "$tmp/on-config.json" > "$tmp/proton-on.out"
grep -Fq 'mesaGlThread=1' "$tmp/proton-on.out" \
  || fail 'explicit launcher.mesaGlThread=true must enable the option'
grep -Fq 'inputPathElision=1' "$tmp/proton-on.out" \
  || fail 'explicit launcher.inputPathElision=true must enable the option'

# An explicit --jvm-option override resolves the WHOLE option before either
# side is emitted: -D...mesaGlThread=false must suppress the mesa_glthread
# export too — glthread without deferred checking is the T22 regression.
"${base[@]}" --jvm-option '-Dturboism.optimization.mesaGlThread=false' \
  > "$tmp/jvm-off.out"
grep -Fq 'mesaGlThread=0' "$tmp/jvm-off.out" \
  || fail 'explicit -D mesaGlThread=false must resolve the effective option off'
if grep -Fq 'linuxEnvironment' "$tmp/jvm-off.out" \
   && grep -Fq 'mesa_glthread=true' "$tmp/jvm-off.out"; then
  fail 'a JVM opt-out must not leave mesa_glthread=true exported'
fi
printf '{"launcher":{"mesaGlThread":true}}\n' > "$tmp/on-mesa-config.json"
"${base[@]}" --home-config "$tmp/on-mesa-config.json" \
  --jvm-option '-Dturboism.optimization.mesaGlThread=false' \
  > "$tmp/jvm-off-beats-config.out"
grep -Fq 'mesaGlThread=0' "$tmp/jvm-off-beats-config.out" \
  || fail 'explicit -D mesaGlThread=false must beat a config true'
if grep -Fq 'mesa_glthread=true' "$tmp/jvm-off-beats-config.out"; then
  fail 'the deferred/glthread combination must never split on a JVM opt-out'
fi

# A driver pinning the Mesa half via --linux-env resolves the whole option:
# mesa_glthread=true implies the deferred check on the JVM side.
"${base[@]}" --home-config "$tmp/off-config.json" \
  --linux-env 'mesa_glthread=true' > "$tmp/env-pin.out"
grep -Fq 'mesaGlThread=1' "$tmp/env-pin.out" \
  || fail 'an explicit mesa_glthread pin must resolve the whole option on'
grep -Fq 'linuxEnvironment' "$tmp/env-pin.out" \
  && grep -Fq 'mesa_glthread=true' "$tmp/env-pin.out" \
  || fail 'the pinned mesa_glthread export must be preserved'

# The JVM uses the last duplicate and Boolean.parseBoolean is case-insensitive.
"${base[@]}" --jvm-option '-Dturboism.optimization.mesaGlThread=false' \
  --jvm-option '-Dturboism.optimization.mesaGlThread=TRUE' \
  --linux-env 'mesa_glthread=true' > "$tmp/mesa-last.out"
grep -Eq '^jvmOption\.[0-9]+=-Dturboism.optimization.mesaGlThread=true$' "$tmp/mesa-last.out" \
  || fail 'the last JVM boolean must generate the canonical deferred property'
[ "$(grep -Ec '^jvmOption\.[0-9]+=-Dturboism.optimization.mesaGlThread=' "$tmp/mesa-last.out")" = 1 ] \
  || fail 'duplicate Mesa JVM properties were not normalized'
for value in false garbage ''; do
  "${base[@]}" --jvm-option "-Dturboism.optimization.mesaGlThread=$value" > "$tmp/mesa-false.out"
  grep -Fq 'mesaGlThread=0' "$tmp/mesa-false.out" || fail 'Java false semantics must turn both halves off'
  grep -Eq '^jvmOption\.[0-9]+=-Dturboism.optimization.mesaGlThread=false$' "$tmp/mesa-false.out" \
    || fail 'effective false must be propagated explicitly'
  if grep -Fq 'mesa_glthread=true' "$tmp/mesa-false.out"; then fail 'false value left Mesa enabled'; fi
done
"${base[@]}" --jvm-option '-Dturboism.optimization.mesaGlThread' > "$tmp/mesa-bare.out"
grep -Fq 'mesaGlThread=0' "$tmp/mesa-bare.out" || fail 'bare -D property must mean false'

# Resolve prerequisites after all overrides, before exporting either half.
for option in '-Dturboism.optimization.uniformLocationCache=false' \
              '-Dturboism.validation.glGetErrorElision=TRUE'; do
  "${base[@]}" --linux-env 'mesa_glthread=true' --jvm-option "$option" > "$tmp/mesa-dependency.out"
  grep -Fq 'mesaGlThread=0' "$tmp/mesa-dependency.out" || fail 'disabled dependency must turn Mesa off'
  grep -Fq 'mesa_glthread=false' "$tmp/mesa-dependency.out" || fail 'explicit Mesa pin must reflect disabled dependency'
  grep -Eq '^jvmOption\.[0-9]+=-Dturboism.optimization.mesaGlThread=false$' "$tmp/mesa-dependency.out" \
    || fail 'disabled dependency must propagate to the JVM'
done
for setting in '{"safeMode":true}' \
               '{"hooks":{"disabledIds":["cubism.render.uniform-location-cache"]}}' \
               '{"hooks":{"disabledIds":["cubism.render.mesa-gl-thread"]}}' \
               '{"launcher":{"uniformLocationCache":false}}'; do
  python3 - "$repo_root/packaging/windows-installer/config.template.json" "$setting" "$tmp/dependency.json" <<'JSON'
import json, sys
config = json.load(open(sys.argv[1]))
config.update(json.loads(sys.argv[2]))
with open(sys.argv[3], 'w') as stream:
    json.dump(config, stream)
JSON
  "${base[@]}" --home-config "$tmp/dependency.json" > "$tmp/mesa-config-dependency.out"
  grep -Fq 'mesaGlThread=0' "$tmp/mesa-config-dependency.out" || fail 'config dependency must disable Mesa'
  if grep -Fq 'mesa_glthread=true' "$tmp/mesa-config-dependency.out"; then fail 'config dependency left Mesa enabled'; fi
done
"${base[@]}" --home-config "$tmp/dependency.json" \
  --jvm-option '-Dturboism.optimization.uniformLocationCache=true' > "$tmp/uniform-override.out"
grep -Fq 'mesaGlThread=1' "$tmp/uniform-override.out" || fail 'last explicit uniform override must precede dependency resolution'
printf '{malformed\n' > "$tmp/invalid-config.json"
"${base[@]}" --home-config "$tmp/invalid-config.json" > "$tmp/mesa-invalid-config.out"
grep -Fq 'mesaGlThread=0' "$tmp/mesa-invalid-config.out" || fail 'unreadable config must not assume admitted hooks'
expect_rejected mesa-nonboolean 'mesa_glthread must be true or false' \
  "${base[@]}" --linux-env 'mesa_glthread=TRUE'

# Contradicting explicit overrides are refused outright, never split.
expect_rejected mesa-split-false 'conflicting mesaGlThread overrides' \
  "${base[@]}" --jvm-option '-Dturboism.optimization.mesaGlThread=false' \
  --linux-env 'mesa_glthread=true'
expect_rejected mesa-split-true 'conflicting mesaGlThread overrides' \
  "${base[@]}" --jvm-option '-Dturboism.optimization.mesaGlThread=true' \
  --linux-env 'mesa_glthread=false'
expect_rejected linux-env-format 'Linux environment assignment must use NAME=value' \
  "${base[@]}" --linux-env 'mesa_glthread'
expect_rejected linux-env-name 'not an admitted Mesa debug variable' \
  "${base[@]}" --linux-env 'LD_PRELOAD=/tmp/pwned.so'
expect_rejected linux-env-path 'not an admitted Mesa debug variable' \
  "${base[@]}" --linux-env 'PATH=/tmp/pwned'
expect_rejected linux-env-lowercase 'not an admitted Mesa debug variable' \
  "${base[@]}" --linux-env 'Mesa_Debug=1'
expect_rejected linux-env-mesa-lowercase 'not an admitted Mesa debug variable' \
  "${base[@]}" --linux-env 'MESA_debug=1'
expect_rejected linux-env-empty 'must use NAME=value' \
  "${base[@]}" --linux-env 'mesa_glthread='
expect_rejected linux-env-value-metachar 'unsupported character' \
  "${base[@]}" --linux-env 'mesa_glthread=true;touch-pwned'
expect_rejected linux-env-value-substitution 'unsupported character' \
  "${base[@]}" --linux-env 'mesa_glthread=$(touch-pwned)'
expect_rejected linux-env-duplicate 'duplicate Linux environment name' \
  "${base[@]}" --linux-env 'mesa_glthread=true' --linux-env 'mesa_glthread=false'

# A rejected request must not reach either legacy transport name.
bin="$tmp/bin"
mkdir -p "$bin"
for transport in ssh scp; do
  cat > "$bin/$transport" <<'SH'
#!/usr/bin/env bash
: "${TRANSPORT_MARKER:?}"
touch "$TRANSPORT_MARKER"
exit 99
SH
  chmod +x "$bin/$transport"
done
expect_rejected before-transport 'trigger path must contain only ASCII' \
  env PATH="$bin:$PATH" TRANSPORT_MARKER="$tmp/transport-used" \
  bash "$runner" --name arg-contract --version 5302 --bundle-root "$bundle" --agent "$bundle/agent.jar" \
  --plugin "$bundle/probe.jar" --fixture-host "$tmp/fixture.cmo3" --result-file state/result.txt \
  --trigger 'state/trigger;touch-pwned' "${host_args[@]}"
[ ! -e "$tmp/transport-used" ] || fail 'rejected path reached a legacy transport'

# Cleanup must bind candidates to recorded /proc start times, fail closed on
# unreadable scans, and never use an unbound process tree or broad Wine kill.
python3 - "$runner" <<'PY' || exit 1
from pathlib import Path
import re
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8")
match = re.search(
    r"remote_stop_process_tree\(\) \{\n(?P<body>.*?)\n\}\n\nlatest_runtime_log\(\)",
    source,
    re.DOTALL,
)
if match is None:
    raise SystemExit("cleanup function not found")
body = match.group("body")
for forbidden in (
    'task in raw',
    'prefix in raw',
    'proc.name.encode() in tracked',
    'kill -KILL',
    'kill -TERM',
    'ps -o pid= --ppid',
    'WINEPREFIX="$prefix_dir/pfx" "$wineserver" -k',
):
    if forbidden in body:
        raise SystemExit(f"unsafe cleanup pattern remains: {forbidden}")
for required in (
    'process_start_time',
    'pid-reused-before-signal',
    'protected ancestor candidate',
    'scan_owned_processes',
    'TURBOISM_HOST_VALIDATION_TASK_DIR',
    'value == prefix',
    'stable_empty',
    'pidfd_open',
    'pidfd_send_signal',
    'late-born or detached task processes cannot be excluded',
    'owned process scan failed',
):
    if required not in body:
        raise SystemExit(f"missing cleanup guard: {required}")
if 'add_process "$pid" "$expected_start" owned-scan' not in body:
    raise SystemExit('scanner candidates are not start-time bound')
if 'unbound process candidate' not in body:
    raise SystemExit('unbound candidates are not fail-closed')
PY

# Hooks receive the complete local context, are recorded as task-owned work, and
# are not run by --prepare-dir.
python3 - "$runner" <<'PY' || exit 1
from pathlib import Path
import re
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8")
match = re.search(r"run_remote_hook\(\) \{\n(?P<body>.*?)\n\}\n\nwrite_lifecycle_result\(\)", source, re.DOTALL)
if match is None:
    raise SystemExit("hook function not found")
body = match.group("body")
for required in (
    '"$task_hook" "${hook_context[@]}" "${expanded_hook_args[@]}"',
    'background-hook.pid',
    'record_owned_process_identity "$!" background-hook',
    'TURBOISM_HOST_VALIDATION_TASK_DIR="$task_dir"',
):
    if required not in body:
        raise SystemExit(f"missing hook lifecycle guard: {required}")
PY

echo 'PASS: Cubism host-validation argument and ownership hardening'
