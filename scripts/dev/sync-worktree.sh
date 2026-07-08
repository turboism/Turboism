#!/usr/bin/env bash
# Worktree-isolated sync script for the current Gradle layout.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "$WT_ROOT"

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
usage: ./scripts/dev/sync-worktree.sh [worktree-id]
       ./scripts/dev/sync-worktree.sh --id <worktree-id>

Copies this worktree's build artifacts and optional launchers into a worktree-scoped drop.
Set TURBOISM_WINDOWS_DROP_ROOT or TURBOISM_WINDOWS_WORKTREES_ROOT.
EOF
}

requested_id=""
case "${1:-}" in
  -h|--help)
    usage
    exit 0
    ;;
  --id)
    requested_id="${2:-}"
    [ -n "$requested_id" ] || { usage >&2; exit 1; }
    shift 2
    ;;
  '')
    ;;
  *)
    requested_id="$1"
    shift
    ;;
esac
[ "$#" -eq 0 ] || { usage >&2; exit 1; }
[ -z "$requested_id" ] || export TURBOISM_WORKTREE_ID="$requested_id"
WT_ID="$(bash "$SCRIPT_DIR/worktree-id.sh")"

BUILD_ROOT="${WT_ROOT}/build/worktree/${WT_ID}"
RUNTIME_JAR="${BUILD_ROOT}/runtime/libs/runtime-0.1.0-SNAPSHOT-${WT_ID}.jar"
SDK_JAR="${BUILD_ROOT}/sdk/libs/sdk-0.1.0-SNAPSHOT-${WT_ID}.jar"
DEMO_PLUGIN_JAR="${BUILD_ROOT}/demo/libs/demo-0.1.0-SNAPSHOT-${WT_ID}.jar"
TESTFRAMEWORK_JAR="${BUILD_ROOT}/testframework/libs/testframework-0.1.0-SNAPSHOT-${WT_ID}.jar"

[ -f "$RUNTIME_JAR" ] || die "runtime jar not found: $RUNTIME_JAR\nrun: ./scripts/dev/build-worktree.sh"
[ -f "$SDK_JAR" ] || die "sdk jar not found: $SDK_JAR\nrun: ./scripts/dev/build-worktree.sh"
[ -f "$DEMO_PLUGIN_JAR" ] || die "demo plugin jar not found: $DEMO_PLUGIN_JAR\nrun: ./scripts/dev/build-worktree.sh"

drop_root="${TURBOISM_WINDOWS_DROP_ROOT:-}"
worktrees_root="${TURBOISM_WINDOWS_WORKTREES_ROOT:-}"

[ -n "$drop_root" ] || [ -n "$worktrees_root" ] || die "set TURBOISM_WINDOWS_DROP_ROOT or TURBOISM_WINDOWS_WORKTREES_ROOT"

join_drop_path() {
  local result="$1"
  shift
  for part in "$@"; do
    result="${result%/}"
    result="$result/$part"
  done
  printf '%s\n' "$result"
}

if [ -n "$worktrees_root" ]; then
  WT_ROOT_REMOTE="$(join_drop_path "$worktrees_root" "$WT_ID")"
else
  WT_ROOT_REMOTE="$(join_drop_path "$drop_root" worktrees "$WT_ID")"
fi

WT_RUNTIME_DROP="$(join_drop_path "$WT_ROOT_REMOTE" runtime "$WT_ID")"
WT_SDK_DROP="$(join_drop_path "$WT_ROOT_REMOTE" sdk "$WT_ID")"
WT_PLUGINS_DROP="$(join_drop_path "$WT_ROOT_REMOTE" plugins "$WT_ID")"
WT_LAUNCH_DROP="$(join_drop_path "$WT_ROOT_REMOTE" launch)"
WT_RUNTIME_CONFIG_DROP="$(join_drop_path "$WT_ROOT_REMOTE" runtime config)"
WT_RUNTIME_LOGS_DROP="$(join_drop_path "$WT_ROOT_REMOTE" runtime logs)"
WT_RUNTIME_STATE_DROP="$(join_drop_path "$WT_ROOT_REMOTE" runtime state)"

printf '[sync] worktree: %s\n' "$WT_ID"
printf '[sync] root:     %s\n' "$WT_ROOT_REMOTE"

mkdir -p "$WT_RUNTIME_DROP" "$WT_SDK_DROP" "$WT_PLUGINS_DROP" "$WT_LAUNCH_DROP" "$WT_RUNTIME_CONFIG_DROP" "$WT_RUNTIME_LOGS_DROP" "$WT_RUNTIME_STATE_DROP"

cp -f "$RUNTIME_JAR" "$WT_RUNTIME_DROP/"
cp -f "$SDK_JAR" "$WT_SDK_DROP/"
cp -f "$DEMO_PLUGIN_JAR" "$WT_PLUGINS_DROP/"
[ -f "$TESTFRAMEWORK_JAR" ] && cp -f "$TESTFRAMEWORK_JAR" "$WT_RUNTIME_DROP/"

[ -f "$WT_ROOT/launch_worktree.ps1" ] && cp -f "$WT_ROOT/launch_worktree.ps1" "$WT_LAUNCH_DROP/"
[ -f "$WT_ROOT/launch_worktree.bat" ] && cp -f "$WT_ROOT/launch_worktree.bat" "$WT_LAUNCH_DROP/"

# Generate runtime config from template if not present.
CONFIG_FILE="$WT_ROOT/turboism.$WT_ID.config.json"
if [ ! -f "$CONFIG_FILE" ] && [ -f "$WT_ROOT/turboism.worktree.config.template.json" ]; then
  python3 - "$WT_ROOT/turboism.worktree.config.template.json" "$CONFIG_FILE" "$WT_ID" "$WT_ROOT_REMOTE/runtime" <<'PY'
from pathlib import Path
import sys

template = Path(sys.argv[1])
out = Path(sys.argv[2])
worktree_id = sys.argv[3]
runtime_dir = sys.argv[4]
text = template.read_text()
text = text.replace("${worktreeId}", worktree_id).replace("${runtimeDir}", runtime_dir)
out.write_text(text)
PY
fi
[ -f "$CONFIG_FILE" ] && cp -f "$CONFIG_FILE" "$WT_RUNTIME_CONFIG_DROP/"

printf '[sync] done\n'
