#!/usr/bin/env bash
# Remote exact-host setup for the validation-only fx bridge and task-local Codex profile.
set -euo pipefail

task_dir=$1
home_dir=$2
evidence_dir=$3
prefix_dir=$4
fixture_path=$5
run_id=$6
host_version=$7
result_timeout=$8

[[ "$result_timeout" =~ ^[1-9][0-9]*$ ]] || {
  printf '%s\n' 'fx bridge accept timeout must be a positive integer' >&2
  exit 1
}
accept_timeout=$((result_timeout + 120))
[ "$accept_timeout" -le 86400 ] || accept_timeout=86400

case "$host_version" in
  5203)
    java_exe='C:\Program Files\Live2D Cubism 5.2\app\jre\bin\java.exe'
    java_property='C:\\Program Files\\Live2D Cubism 5.2\\app\\jre\\bin\\java.exe'
    ;;
  5302)
    java_exe='C:\Program Files\Live2D Cubism 5.3\app\jre\bin\java.exe'
    java_property='C:\\Program Files\\Live2D Cubism 5.3\\app\\jre\\bin\\java.exe'
    ;;
  *) printf '%s\n' 'unsupported Cubism host version' >&2; exit 1 ;;
esac

archive_url='https://github.com/vercel-labs/fx/releases/download/v0.0.5/fx-linux-x86_64.tar.gz'
archive_sha256='d5639d173267774aa8228a474baf619a7076ac41a91023915007c865143429b1'
executable_sha256='27a5e9474fd749d6ca2503ab93765176a93ffbd0f0e7173e8f2e3e4c6b51876f'
bridge_runtime="$task_dir/fx-bridge-runtime"
fx_home="$task_dir/fx-home"
fx_install="$task_dir/fx-install"
fx_archive="$task_dir/fx-linux-x86_64.tar.gz"
token_file="$bridge_runtime/token"
broker_script="$home_dir/state/dev.turboism.validation.fx-host/fx_validation_broker.py"
bridge_jar="$home_dir/state/dev.turboism.plugin.turboism-with-fx/fx-validation-bridge.jar"
state_dir="$home_dir/state/dev.turboism.plugin.turboism-with-fx"

umask 077
config_dir="$home_dir/config/dev.turboism.plugin.turboism-with-fx"
mkdir -p "$bridge_runtime" "$fx_home/.fx" "$fx_install" "$state_dir" \
  "$config_dir" "$evidence_dir"
chmod 700 "$bridge_runtime" "$fx_home" "$fx_home/.fx" "$fx_install" "$state_dir" \
  "$config_dir"

[ -s "$broker_script" ] && [ -s "$bridge_jar" ] || {
  printf '%s\n' 'required bridge artifacts are missing' >&2
  exit 1
}

curl --fail --silent --show-error --location "$archive_url" --output "$fx_archive"
[ "$(sha256sum "$fx_archive" | cut -d' ' -f1)" = "$archive_sha256" ]
tar --extract --gzip --file "$fx_archive" --directory "$fx_install" fx LICENSE THIRD_PARTY_NOTICES.md
chmod 700 "$fx_install/fx"
[ "$(sha256sum "$fx_install/fx" | cut -d' ' -f1)" = "$executable_sha256" ]
[ "$($fx_install/fx --version)" = '0.0.5' ]

pi_home="${HOME:?}"
credential_json="$(HOME="$pi_home" pi auth check \
  --provider openai-codex \
  --model gpt-5.3-codex-spark \
  --json \
  --credentials)"
CREDENTIAL_JSON="$credential_json" FX_AUTH_FILE="$fx_home/.fx/chatgpt-auth.json" python3 - <<'PY'
import base64
import json
import os
from pathlib import Path
import time

raw = json.loads(os.environ.pop("CREDENTIAL_JSON"))
candidates = []

def visit(value):
    if isinstance(value, str):
        if value.count(".") == 2 and len(value) > 128:
            candidates.append(value)
    elif isinstance(value, dict):
        for child in value.values():
            visit(child)
    elif isinstance(value, list):
        for child in value:
            visit(child)

visit(raw)
access = next((value for value in candidates if value.startswith("eyJ")), None)
if access is None:
    raise SystemExit("Pi did not return a usable Codex credential")
try:
    payload = access.split(".")[1]
    payload += "=" * (-len(payload) % 4)
    claims = json.loads(base64.urlsafe_b64decode(payload))
except Exception as failure:
    raise SystemExit("Pi Codex credential metadata is invalid") from failure
expiry = int(claims.get("exp", 0))
if expiry - int(time.time()) < 1800:
    raise SystemExit("Pi Codex credential lifetime is below the validation budget")
auth_claims = claims.get("https://api.openai.com/auth")
account_id = auth_claims.get("chatgpt_account_id") if isinstance(auth_claims, dict) else None
if not isinstance(account_id, str) or not account_id:
    raise SystemExit("Pi Codex account id is missing")
path = Path(os.environ["FX_AUTH_FILE"])
path.write_text(json.dumps({
    "version": 1,
    "access_token": access,
    "refresh_token": "turboism-validation-no-refresh",
    "expires_at_ms": expiry * 1000,
    "account_id": account_id,
}, separators=(",", ":")), encoding="utf-8")
path.chmod(0o600)
PY
unset credential_json

printf '%s\n' '{"provider":"codex","codex_model":"gpt-5.3-codex-spark","permission_mode":"ask"}' \
  > "$fx_home/.fx/settings.json"
chmod 600 "$fx_home/.fx/settings.json" "$fx_home/.fx/chatgpt-auth.json"

python3 - <<'PY' "$token_file"
from pathlib import Path
import secrets
import sys
path = Path(sys.argv[1])
path.write_text(secrets.token_urlsafe(32), encoding="ascii")
path.chmod(0o600)
PY
session_id="$(python3 - <<'PY'
import secrets
value = secrets.token_urlsafe(32)
if value.startswith('-'):
    value = 'A' + value[1:]
print(value)
PY
)"

printf 'fxExecutable=%s\nallowFxNativeTools=true\ninitialPrompt=%s\n' \
  "$java_property" \
  'Perform only the exact requested Turboism MCP operation and make no extra changes.' \
  > "$config_dir/settings.properties"
chmod 600 "$config_dir/settings.properties"

nohup python3 "$broker_script" \
  --runtime-dir "$bridge_runtime" \
  --session-id "$session_id" \
  --token-file "$token_file" \
  --fx-home "$fx_home" \
  --fx-executable "$fx_install/fx" \
  --accept-timeout-seconds "$accept_timeout" \
  </dev/null >"$evidence_dir/fx-broker.out" 2>"$evidence_dir/fx-broker.err" &
printf '%s\n' "$!" > "$bridge_runtime/broker.pid"
chmod 600 "$bridge_runtime/broker.pid"

for _ in $(seq 1 200); do
  [ -s "$bridge_runtime/fx-validation-bridge.properties" ] && break
  kill -0 "$(cat "$bridge_runtime/broker.pid")" 2>/dev/null || {
    printf '%s\n' 'fx broker exited before publishing its descriptor' >&2
    exit 1
  }
  sleep 0.05
done
[ -s "$bridge_runtime/fx-validation-bridge.properties" ]
cp "$bridge_runtime/fx-validation-bridge.properties" "$state_dir/fx-validation-bridge.properties"
chmod 600 "$state_dir/fx-validation-bridge.properties"

{
  printf 'schemaVersion=1\n'
  printf 'runId=%s\n' "$run_id"
  printf 'hostVersion=%s\n' "$host_version"
  printf 'fxArchiveSha256=%s\n' "$archive_sha256"
  printf 'fxExecutableSha256=%s\n' "$executable_sha256"
  printf 'fxVersion=0.0.5\n'
  printf 'provider=codex\n'
  printf 'model=gpt-5.3-codex-spark\n'
  printf 'javaExecutable=%s\n' "$java_exe"
  printf 'status=READY\n'
} > "$evidence_dir/fx-validation-preflight.properties"
chmod 600 "$evidence_dir/fx-validation-preflight.properties"
