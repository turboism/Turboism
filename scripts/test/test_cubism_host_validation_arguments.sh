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
printf 'key\n' > "$tmp/key"
host_args=(--ssh-host test@example.invalid --ssh-key "$tmp/key"
  --golden-prefix /tmp/turboism-golden --remote-root /tmp/turboism-validation
  --proton-runner /tmp/proton)

base=(bash "$runner" --name arg-contract --version 5302 --bundle-root "$bundle"
  --agent "$bundle/agent.jar" --plugin "$bundle/probe.jar" --fixture-local "$tmp/fixture.cmo3"
  --result-file state/result.txt "${host_args[@]}" --dry-run)

cubism_java='Z:\home\local-user\TurboismValidation\tools\graalvm-25.2.4\bin\java.exe'
"${base[@]}" --home-file "$tmp/home-file.txt:scripts/input.txt" --home-dir "$tmp/home-dir:scripts" \
  --trigger state/trigger.flag --windows-env 'HOME={HOME}\\fx-home' \
  --windows-env 'USERPROFILE={HOME}\\fx-home' --cubism-java "$cubism_java" \
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
expect_rejected ssh-option-prefix 'SSH host must not begin with an option prefix' \
  "${base[@]}" --ssh-host '-oProxyCommand=touch-pwned'
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
  "${base[@]}" --windows-env 'HOME=C:\\safe&whoami'

# A non-dry run must not interpolate attacker-controlled path text into the SSH
# command. The validation rejects it before trying either transport stub.
bin="$tmp/bin"
mkdir -p "$bin"
cat > "$bin/ssh" <<'SH'
#!/usr/bin/env bash
: "${SSH_MARKER:?}"
touch "$SSH_MARKER"
exit 99
SH
chmod +x "$bin/ssh"
expect_rejected before-ssh 'trigger path must contain only ASCII' env PATH="$bin:$PATH" SSH_MARKER="$tmp/ssh-used" \
  bash "$runner" --name arg-contract --version 5302 --bundle-root "$bundle" --agent "$bundle/agent.jar" \
  --plugin "$bundle/probe.jar" --fixture-local "$tmp/fixture.cmo3" --result-file state/result.txt \
  --trigger 'state/trigger;touch-pwned' "${host_args[@]}"
[ ! -e "$tmp/ssh-used" ] || fail 'rejected path reached SSH transport'

# Cleanup must pass task-scoped paths through the runner's Base64 argument
# transport. Embedding them in the remote `bash -s -- ...` command causes the
# process scan to match and kill its own cleanup coordinator.
python3 - "$runner" <<'PY' || fail 'remote cleanup embeds task paths in its command line'
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
    raise SystemExit(1)
body = match.group("body")
if 'remote_args_bash "$evidence_dir/wrapper.pid" "$prefix_dir/pfx" "$proton_runner"' not in body:
    raise SystemExit(1)
if '"${ssh_cmd[@]}" "$ssh_host" "bash -s --' in body:
    raise SystemExit(1)
if "<<'REMOTE' || true" in body:
    raise SystemExit("process cleanup failure must propagate")
for marker in ('WINEPREFIX={prefix}', 'Path("/proc").iterdir()', 'prefix.encode() in raw'):
    if marker not in body:
        raise SystemExit(marker)
PY

# Feature hooks receive exact host timing and Proton launch context as positional
# arguments. This lets task-local native-runtime setup use the same reviewed runner
# and cloned prefix without reading global environment or wrapper configuration.
python3 - "$runner" <<'PY' || fail 'remote hooks do not receive host launch context'
from pathlib import Path
import re
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8")
match = re.search(
    r"run_remote_hook\(\) \{\n(?P<body>.*?)\n\}\n\non_exit\(\)",
    source,
    re.DOTALL,
)
if match is None:
    raise SystemExit(1)
body = match.group("body")
if '"$version" "$result_timeout" "$proton_wrapper" "$proton_runner" "$display"' not in body:
    raise SystemExit(1)
if '"${REMOTE_ARGS[5]}" "${REMOTE_ARGS[6]}"' not in body:
    raise SystemExit(1)
if '"${REMOTE_ARGS[7]}" "${REMOTE_ARGS[8]}" "${REMOTE_ARGS[9]}" "${REMOTE_ARGS[10]}"' not in body:
    raise SystemExit(1)
PY

echo 'PASS: Cubism host-validation argument hardening'
