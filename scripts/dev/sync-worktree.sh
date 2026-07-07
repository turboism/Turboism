#!/usr/bin/env bash
# Worktree-isolated sync script
# Adapted from ../turboism-legacy/sync_worktree.sh for the new Gradle multi-module layout.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WT_ROOT="$(cd "${SCRIPT_DIR}/../.." && pwd)"
cd "$WT_ROOT"

WT_ID="$(bash "$SCRIPT_DIR/worktree-id.sh")"

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

usage() {
  cat <<'EOF'
usage: ./scripts/dev/sync-worktree.sh [worktree-id]
       ./scripts/dev/sync-worktree.sh --id <worktree-id>

Copies this worktree's build artifacts and launchers into the configured Windows drop.
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
[ -z "$requested_id" ] || TURBOISM_WORKTREE_ID="$requested_id"
WT_ID="$(bash "$SCRIPT_DIR/worktree-id.sh")"

BUILD_ROOT="${WT_ROOT}/build/worktree/${WT_ID}"
AGENT_JAR="${BUILD_ROOT}/turboism-bootstrap-agent/libs/turboism-bootstrap-agent-0.1.0-SNAPSHOT-${WT_ID}.jar"
SDK_GLOB="${BUILD_ROOT}/turboism-sdk/libs/turboism-sdk-0.1.0-SNAPSHOT-${WT_ID}.jar"
PLUGINS_DIR="${WT_ROOT}/modules/official-plugins"

[ -f "$AGENT_JAR" ] || die "agent jar not found: $AGENT_JAR\nrun: ./scripts/dev/build-worktree.sh"

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

WT_AGENT_DROP="$(join_drop_path "$WT_ROOT_REMOTE" agent "$WT_ID")"
WT_PLUGINS_DROP="$(join_drop_path "$WT_ROOT_REMOTE" plugins "$WT_ID")"
WT_LAUNCH_DROP="$(join_drop_path "$WT_ROOT_REMOTE" launch)"
WT_RUNTIME_CONFIG_DROP="$(join_drop_path "$WT_ROOT_REMOTE" runtime config)"
WT_RUNTIME_LOGS_DROP="$(join_drop_path "$WT_ROOT_REMOTE" runtime logs)"
WT_RUNTIME_STATE_DROP="$(join_drop_path "$WT_ROOT_REMOTE" runtime state)"

printf '[sync] worktree: %s\n' "$WT_ID"
printf '[sync] root:     %s\n' "$WT_ROOT_REMOTE"

mkdir -p "$WT_AGENT_DROP" "$WT_PLUGINS_DROP" "$WT_LAUNCH_DROP" "$WT_RUNTIME_CONFIG_DROP" "$WT_RUNTIME_LOGS_DROP" "$WT_RUNTIME_STATE_DROP"

# Copy agent jar
cp -f "$AGENT_JAR" "$WT_AGENT_DROP/"

# Copy SDK jar
[ -f "$SDK_GLOB" ] && cp -f "$SDK_GLOB" "$WT_AGENT_DROP/"

# Copy plugin jars
for plugin_jar in "$PLUGINS_DIR"/*/build/worktree/"$WT_ID"/libs/*-"$WT_ID".jar; do
  [ -f "$plugin_jar" ] && cp -f "$plugin_jar" "$WT_PLUGINS_DROP/"
done

# Copy launchers
[ -f "$WT_ROOT/launch_worktree.ps1" ] && cp -f "$WT_ROOT/launch_worktree.ps1" "$WT_LAUNCH_DROP/"
[ -f "$WT_ROOT/launch_worktree.bat" ] && cp -f "$WT_ROOT/launch_worktree.bat" "$WT_LAUNCH_DROP/"

# Generate runtime config from template if not present
CONFIG_FILE="$WT_ROOT/turboism.$WT_ID.config.json"
if [ ! -f "$CONFIG_FILE" ] && [ -f "$WT_ROOT/turboism.worktree.config.template.json" ]; then
  sed "s/\${worktreeId}/$WT_ID/g; s|\${runtimeDir}|$WT_ROOT_REMOTE/runtime|g" \
    "$WT_ROOT/turboism.worktree.config.template.json" > "$CONFIG_FILE"
fi
[ -f "$CONFIG_FILE" ] && cp -f "$CONFIG_FILE" "$WT_RUNTIME_CONFIG_DROP/"

printf '[sync] done\n'
