#!/usr/bin/env bash
# Offline security contract for run-cubism-host-validation.sh.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/../.." && pwd -P)"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
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
