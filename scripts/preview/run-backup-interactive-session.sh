#!/usr/bin/env bash
# Interactive WebDAV auto-backup session for the exact-host runner: loads the
# agent + the backup plugin + one fixture copy and keeps the task-scoped
# prefix, so a human can fill the WebDAV settings dialog, save a document,
# observe the save-triggered backup artifact, and confirm the WebDAV upload.
# The runner PASS/FAIL here is shell behavior only: the result file is never
# written, so a non-PASS outcome is expected once the editor is closed.
#
# Optional: --webdav-config <local-file> stages a saved backup/webdav.cfg into
# the task-scoped turboism home (config/dev.turboism.plugin.backup/backup/
# webdav.cfg) right after the runner creates the task dir, so the session
# reuses a previously saved WebDAV configuration. Uses the runner's default
# SSH host/key/remote root unless --ssh-host/--ssh-key/--remote-root are given.
set -euo pipefail

# Machine-specific fixture paths come from the ignored repository `.env`.
# shellcheck source=host-validation-env.sh
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/host-validation-env.sh"

if [ "$#" -lt 1 ]; then
  echo "usage: run-backup-interactive-session.sh <5302|5203> [run-label] [runner-options...] [--webdav-config <file>]" >&2
  exit 2
fi
version="$1"
shift
run_label="webdav-manual"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

turboism_select_fixture "$version" || exit 2

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
backup_plugin_jar="$repo_root/build/worktree/$worktree_id/backup/libs/backup-0.1.0-SNAPSHOT-$worktree_id.jar"
runner="$repo_root/scripts/preview/run-cubism-host-validation.sh"

if [ ! -f "$agent_jar" ]; then
  echo "error: agent jar not found at $agent_jar; run previewBundle first" >&2
  exit 1
fi
if [ ! -f "$backup_plugin_jar" ]; then
  echo "error: backup plugin jar not found at $backup_plugin_jar; run :plugins:backup:jar first" >&2
  exit 1
fi

# Wrapper-local options: --webdav-config plus the ssh placement knobs that
# decide where the task dir lands (all other options pass through to the runner).
webdav_config=''
ssh_host="$TURBOISM_HOST_VALIDATION_SSH_HOST"
ssh_key="$TURBOISM_HOST_VALIDATION_SSH_KEY"
remote_root="$TURBOISM_HOST_VALIDATION_REMOTE_ROOT"
runner_args=()
while [ "$#" -gt 0 ]; do
  case "$1" in
    --webdav-config)
      [ "$#" -ge 2 ] || { echo "error: --webdav-config requires a file path" >&2; exit 2; }
      webdav_config="$2"
      shift 2
      ;;
    --ssh-host|--ssh-key|--remote-root)
      [ "$#" -ge 2 ] || { echo "error: $1 requires a value" >&2; exit 2; }
      case "$1" in
        --ssh-host) ssh_host="$2" ;;
        --ssh-key) ssh_key="$2" ;;
        --remote-root) remote_root="$2" ;;
      esac
      runner_args+=("$1" "$2")
      shift 2
      ;;
    *) runner_args+=("$1"); shift ;;
  esac
done

echo "interactive-session: opening a WebDAV backup session (fixture auto-opens)."
echo "  - fill Turboism/WebDAV 备份设置 and save; modify and save the document;"
echo "  - observe the backup artifact and the WebDAV upload;"
echo "  - close the editor window when done (prefix is kept for evidence)."
if [ -n "$webdav_config" ]; then
  [ -f "$webdav_config" ] || { echo "error: --webdav-config file not found: $webdav_config" >&2; exit 1; }
  echo "webdav-config: staging $webdav_config into the task turboism-home config dir."
fi

run_nonce="${TURBOISM_HOST_VALIDATION_RUN_NONCE:-$(printf '%06d' "$$")}"
runner_cmd=(
  env "TURBOISM_HOST_VALIDATION_RUN_NONCE=$run_nonce"
  bash "$runner"  --name backup-interactive
  --version "$version"
  --run-label "$run_label"
  --bundle-root "$repo_root/build/preview/$worktree_id"
  --agent "$agent_jar"
  --plugin "$backup_plugin_jar:backup.jar"
  --fixture-remote "$fixture_src"
  --fixture-sha256 "$fixture_sha256"
  --require-fixture-unchanged
  --ready-marker 'Turboism Developer Preview started'
  --result-file 'state/interactive-session.result'
  --result-pass-line 'status=PASS'
  --result-fail-line 'status=FAIL'
  --ready-timeout 480
  --result-timeout 86400
  --exit-timeout 600
  --keep-prefix
)
# Base runner args first; user-supplied options come last so they win on
# duplicates (same precedence as the original `exec ... "$@"` tail).
all_args=("${runner_cmd[@]}" "${runner_args[@]}")

"${all_args[@]}" &
runner_pid=$!
cleanup() {
  if kill -0 "$runner_pid" 2>/dev/null; then
    kill "$runner_pid" 2>/dev/null || true
  fi
}
trap cleanup INT TERM

staged=0
if [ -n "$webdav_config" ]; then
  ssh_cmd=(ssh -i "$ssh_key" -o IdentitiesOnly=yes -o ConnectTimeout=10)
  scp_cmd=(scp -i "$ssh_key" -o IdentitiesOnly=yes)
  task_base="$remote_root/backup-interactive/$version-$run_label"
  # Match only this invocation's nonce-bearing task ID. Multiple interactive or
  # automated sessions may create sibling task directories concurrently.
  for _ in $(seq 1 120); do
    kill -0 "$runner_pid" 2>/dev/null || break
    candidate="$("${ssh_cmd[@]}" "$ssh_host" \
      "find '$task_base' -mindepth 1 -maxdepth 1 -type d -name 'backup-interactive-*-$run_nonce' -print -quit 2>/dev/null" || true)"
    if [ -n "$candidate" ]; then
      remote_home="$candidate/turboism-home"
      if "${ssh_cmd[@]}" "$ssh_host" "[ -d '$remote_home' ]"; then
        target_dir="$remote_home/config/dev.turboism.plugin.backup/backup"
        "${ssh_cmd[@]}" "$ssh_host" "mkdir -p '$target_dir'"
        "${scp_cmd[@]}" "$webdav_config" "$ssh_host:$target_dir/webdav.cfg"
        echo "webdav-config: staged -> $target_dir/webdav.cfg"
        staged=1
        break
      fi
    fi
    sleep 2
  done
  if [ "$staged" = 0 ]; then
    echo "webdav-config: warning: no task dir appeared before the runner exited; config not staged (dry-run or staging failure)" >&2
  fi
fi

set +e
wait "$runner_pid"
code=$?
set -e
trap - INT TERM
exit "$code"
