#!/usr/bin/env bash
# WebDAV backup end-to-end adapter for the generic exact-host runner: deploys
# the production dev.turboism.plugin.webdav plugin with a seeded
# backup/webdav.cfg pointing at the probe's in-JVM recording WebDAV mock
# (127.0.0.1 only, fixed port so the seeded config resolves), plus the extended
# backup-host-validation exerciser. The exerciser drives the semantic
# EditorCommand.SAVE so the plugin's save-triggered backup->upload->discard
# chain runs against real host primitives; the mock records every request
# (method/path/Content-Length/body bytes/SHA-256) into the plugin state dir,
# which the runner archives under evidence/state. The save deliberately
# rewrites the copied fixture, so this variant must not pass
# --require-fixture-unchanged (the immutable source fixture hash is still
# verified by the Runner).
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-backup-webdav-host-validation.sh <5302|5303> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
run_label="r1"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
probe_jar="$repo_root/build/backup-host-validation-exerciser.jar"
plugin_dir="$repo_root/build/worktree/$worktree_id/webdav-backup/libs"
config_dir="$repo_root/build/backup-webdav"
config_file="$config_dir/webdav.cfg"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"
webdav_port=57843
webdav_remote_path="/turboism-backup"
heap_bytes=134217728

if [ ! -f "$agent_jar" ]; then
  echo "error: agent jar not found at $agent_jar; run previewBundle first" >&2
  exit 1
fi
if [ ! -f "$probe_jar" ]; then
  echo "error: probe jar not found at $probe_jar; run ./gradlew :sdk:jar && validation/backup-host-probe/build.sh first" >&2
  exit 1
fi
shopt -s nullglob
plugin_jars=("$plugin_dir"/webdav-backup-*.jar)
if [ "${#plugin_jars[@]}" -ne 1 ]; then
  echo "error: expected exactly one webdav-backup jar in $plugin_dir; found ${#plugin_jars[@]}; run ./gradlew :plugins:webdav-backup:jar" >&2
  exit 1
fi
plugin_jar="${plugin_jars[0]}"

# The seeded endpoint must be free before submission; the probe binds the same
# 127.0.0.1 port inside the session so the production plugin reaches it.
if ! python3 - "$webdav_port" <<'PY'
import socket
import sys
# SO_REUSEADDR mirrors the JDK ServerSocket bind the probe performs; a stale
# TIME-WAIT from the previous run must not fail the preflight.
sock = socket.socket()
sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
try:
    sock.bind(("127.0.0.1", int(sys.argv[1])))
except OSError:
    raise SystemExit(1)
finally:
    sock.close()
PY
then
  echo "error: 127.0.0.1:$webdav_port is already bound; choose another webdav_port" >&2
  exit 1
fi

python3 "$repo_root/validation/backup-host-probe/render-webdav-config.py" \
  "$config_file" "$webdav_port" || exit 2

exec bash "$runner" \
  --name backup-webdav \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$repo_root/build/preview/$worktree_id" \
  --agent "$agent_jar" \
  --plugin "$probe_jar:backup-host-validation-exerciser.jar" \
  --plugin "$plugin_jar:webdav-backup.jar" \
  --home-file "$config_file:config/dev.turboism.plugin.webdav/backup/webdav.cfg" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --jvm-option '-Dturboism.validation.fixture={FIXTURE}' \
  --jvm-option "-Dturboism.validation.webdav.port=$webdav_port" \
  --jvm-option '-Dturboism.validation.webdav.expect-plugin=1' \
  --jvm-option "-Dturboism.validation.webdav.plugin-path=$webdav_remote_path" \
  --jvm-option "-Dturboism.validation.heap-bytes=$heap_bytes" \
  --ready-marker 'BACKUP_EXERCISER_READY' \
  --result-file 'state/dev.turboism.validation.backup/backup-validation-result.properties' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --failure-marker 'BACKUP_VALIDATION_RESULT status=FAIL' \
  --ready-timeout 480 \
  --result-timeout 1200 \
  --exit-timeout 120 \
  "$@"
