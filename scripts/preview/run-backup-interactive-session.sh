#!/usr/bin/env bash
# Interactive WebDAV auto-backup session for the exact-host runner: loads the
# agent + the backup plugin + one fixture copy and keeps the task-scoped
# prefix, so a human can fill the WebDAV settings dialog, save a document,
# observe the save-triggered backup artifact, and confirm the WebDAV upload.
# The runner PASS/FAIL here is shell behavior only: the result file is never
# written, so a non-PASS outcome is expected once the editor is closed.
set -euo pipefail

if [ "$#" -lt 1 ]; then
  echo "usage: run-backup-interactive-session.sh <5302|5203> [run-label] [runner-options...]" >&2
  exit 2
fi
version="$1"
shift
run_label="webdav-manual"
if [ "$#" -gt 0 ] && [[ "$1" != --* ]]; then
  run_label="$1"
  shift
fi

case "$version" in
  5302)
    fixture_src='<local-home>/Documents/测试 混合模式.cmo3'
    fixture_sha256='57c4854b70f7d5d305b1974f9dc1792cdd7bed616f05621f535b47019d33fbe4'
    ;;
  5203)
    fixture_src='<local-home>/TurboismPartValidation/part52-official/part-opacity-fixture-52-final.cmo3'
    fixture_sha256='331bbb4cbdb1287f5bd063a0661d94c2860534baa7d0f76bb055ed070a21b028'
    ;;
  *)
    echo "error: version must be 5302 or 5203" >&2
    exit 2
    ;;
esac

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

echo "interactive-session: opening a WebDAV backup session (fixture auto-opens)."
echo "  - fill Turboism/WebDAV 备份设置 and save; modify and save the document;"
echo "  - observe the backup artifact and the WebDAV upload;"
echo "  - close the editor window when done (prefix is kept for evidence)."

exec bash "$runner" \
  --name backup-interactive \
  --version "$version" \
  --run-label "$run_label" \
  --bundle-root "$repo_root/build/preview/$worktree_id" \
  --agent "$agent_jar" \
  --plugin "$backup_plugin_jar:backup.jar" \
  --fixture-remote "$fixture_src" \
  --fixture-sha256 "$fixture_sha256" \
  --require-fixture-unchanged \
  --ready-marker 'Turboism Developer Preview started' \
  --result-file 'state/interactive-session.result' \
  --result-pass-line 'status=PASS' \
  --result-fail-line 'status=FAIL' \
  --ready-timeout 480 \
  --result-timeout 86400 \
  --exit-timeout 600 \
  --keep-prefix \
  "$@"
