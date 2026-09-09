#!/usr/bin/env bash
# Local-only Runner regression: prepare/dry-run must not invoke a transport or hook.
set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
repo_root="$(cd -- "$script_dir/../.." && pwd -P)"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
tmp="$(mktemp -d "${TMPDIR:-/tmp}/turboism-cubism-local.XXXXXX")"
trap 'rm -rf -- "$tmp"' EXIT

fail() { printf 'FAIL: %s\n' "$1" >&2; exit 1; }

bundle="$tmp/bundle"
mkdir -p "$bundle" "$tmp/home-dir"
printf 'agent\n' > "$bundle/agent.jar"
printf 'plugin\n' > "$bundle/plugin.jar"
printf 'fixture\n' > "$tmp/fixture.cmo3"
printf '{"test":true}\n' > "$tmp/home.json"
printf 'home-file\n' > "$tmp/home-file"
printf 'home-dir\n' > "$tmp/home-dir/value"
hook="$repo_root/scripts/preview/fps-resize-driver.sh"

common=(bash "$runner" --name local-regression --version 5302 --bundle-root "$bundle"
  --agent "$bundle/agent.jar" --plugin "$bundle/plugin.jar"
  --fixture-host "$tmp/fixture.cmo3" --golden-prefix "$tmp/golden"
  --host-root "$tmp/host-root" --proton-runner "$tmp/proton"
  --home-config "$tmp/home.json" --home-file "$tmp/home-file:input.txt"
  --home-dir "$tmp/home-dir:assets" --remote-pre-launch "$hook"
  --remote-pre-launch-background --remote-pre-launch-args-only
  --remote-pre-launch-arg '{FIXTURE_NAME}' --jvm-option '-Dprobe={TASK_ID}'
  --windows-env 'HOME={HOME}\\home' --result-file state/result.txt)

bin="$tmp/bin"
mkdir -p "$bin"
for command_name in ssh scp; do
  cat > "$bin/$command_name" <<'SH'
#!/usr/bin/env bash
touch "${TRANSPORT_MARKER:?}"
exit 99
SH
  chmod +x "$bin/$command_name"
done
cat > "$bin/seq" <<'SH'
#!/usr/bin/env bash
touch "${LOCAL_HOOK_MARKER:?}"
exit 0
SH
chmod +x "$bin/seq"

prepare_dir="$tmp/prepare"
PATH="$bin:$PATH" TURBOISM_QUEUE_RUN_ID='queued-local-001' \
  LOCAL_HOOK_MARKER="$tmp/hook-ran" "${common[@]}" --prepare-dir "$prepare_dir" \
  > "$tmp/prepare.out"

[ -f "$prepare_dir/runner-request.json" ] || fail 'prepare request was not written'
[ ! -e "$tmp/hook-ran" ] || fail 'prepare mode executed the launch hook'
[ ! -e "$tmp/host-root" ] || fail 'prepare mode created a host task root'
[ ! -e "$tmp/transport-used" ] || fail 'prepare mode reached a legacy transport'
python3 - "$prepare_dir/runner-request.json" <<'PY'
import json
import sys

payload = json.load(open(sys.argv[1], encoding='utf-8'))
assert payload == {"schemaVersion": 1, "argv": payload["argv"], "environment": {}}
argv = payload["argv"]
assert "--prepare-dir" not in argv
assert "--dry-run" not in argv
for required in (
    "--name", "local-regression", "--version", "5302", "--bundle-root",
    "--agent", "--plugin", "--home-config", "--home-file",
    "--home-dir", "--fixture-host", "--fixture-sha256",
    "--golden-prefix", "--host-root", "--local-evidence-dir", "--display",
    ":0", "--proton-wrapper", "--proton-runner", "--agent-timeout", "180",
    "--agent-host-class", "--ready-timeout", "240", "--result-timeout", "300",
    "--exit-timeout", "120", "--poll-seconds", "3", "--remote-pre-launch",
    "--remote-pre-launch-background", "--remote-pre-launch-args-only",
    "--remote-pre-launch-arg", "{FIXTURE_NAME}", "--jvm-option",
    "--windows-env", "--result-file", "--result-pass-line", "status=PASS",
    "--result-fail-line", "status=FAIL", "--transport", "local",
):
    assert required in argv, required
assert argv[argv.index("--name") + 1] == "local-regression"
assert "--fixture-name" not in argv  # Preserve inferred-extension semantics, not an explicit filename.
assert argv[argv.index("--host-root") + 1].endswith("/host-root")
PY

grep -Fq 'preparedRequest=' "$tmp/prepare.out" || fail 'prepare confirmation missing'

PATH="$bin:$PATH" TURBOISM_QUEUE_RUN_ID='queued-local-001' LOCAL_HOOK_MARKER="$tmp/hook-ran-dry" \
  "${common[@]}" --dry-run > "$tmp/dry-run.out"
[ ! -e "$tmp/hook-ran-dry" ] || fail 'dry-run executed the launch hook'
grep -Fq 'transport=local' "$tmp/dry-run.out" || fail 'dry-run did not report local transport'
grep -Fq 'taskId=queued-local-001' "$tmp/dry-run.out" || fail 'queue run ID was not used'
grep -Fq 'remotePreLaunch='"$hook" "$tmp/dry-run.out" || fail 'hook was omitted from dry-run'
python3 - "$runner" "$prepare_dir/runner-request.json" "$tmp/dry-run.out" <<'PY'
import json, os, subprocess, sys
runner, request, original = sys.argv[1:]
env = {**os.environ, 'TURBOISM_QUEUE_RUN_ID': 'queued-local-001'}
replayed = subprocess.check_output(['bash', runner, *json.load(open(request))['argv'], '--dry-run'], env=env, text=True)
expected = dict(line.split('=', 1) for line in open(original).read().splitlines() if '=' in line)
actual = dict(line.split('=', 1) for line in replayed.splitlines() if '=' in line)
for key in ('taskId', 'runId', 'fixtureName', 'fixturePath', 'validationFixtureNameJvmOption'):
    assert actual[key] == expected[key], (key, actual[key], expected[key])
PY

# Naked queue labels must never authorize host side effects.
if TURBOISM_QUEUE_RUN_ID='queued-local-001' "${common[@]}" > "$tmp/admission.out" 2>&1; then
  fail 'bare queue run ID bypassed worker admission'
fi
grep -Fq 'worker admission rejected' "$tmp/admission.out" || fail 'missing admission rejection'
[ ! -e "$tmp/host-root" ] || fail 'invalid admission created host root'

for option in --ssh-host --ssh-key; do
  if PATH="$bin:$PATH" TRANSPORT_MARKER="$tmp/transport-used" \
    "${common[@]}" "$option" 'forbidden' >"$tmp/$option.out" 2>&1; then
    fail "$option unexpectedly succeeded"
  fi
  grep -Fq 'no longer supported in the local-only Runner' "$tmp/$option.out" \
    || fail "$option did not report migration guidance"
done
[ ! -e "$tmp/transport-used" ] || fail 'migration errors reached a legacy transport'

python3 - "$runner" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text(encoding='utf-8')
assert 'ssh_cmd' not in source
assert 'scp_cmd' not in source
assert 'TURBOISM_QUEUE_RUN_ID' in source
assert '"jobId": admission["jobId"]' in source
assert '"preparedDigest": admission["preparedDigest"]' in source
assert 'lifecycle-result.json' in source
PY

echo 'PASS: local-only Cubism Runner transport and prepare regression'
