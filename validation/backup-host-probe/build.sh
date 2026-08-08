#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local auto-backup host validation exerciser plugin JAR against
# the already-built SDK jar. The probe is validation tooling only; it is never
# part of the production preview bundle or product build.
#
# The probe reuses the production WebDAV client sources from plugins/backup so
# the real-host run exercises the exact plugin WebDAV target code (JDK-only).
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
sdk_dir="$repo_root/build/worktree/$worktree_id/sdk/libs"
shopt -s nullglob
sdk_jars=("$sdk_dir"/sdk-*.jar)
if [ "${#sdk_jars[@]}" -ne 1 ] || [ ! -f "${sdk_jars[0]}" ]; then
  echo "error: expected exactly one sdk jar in $sdk_dir; found ${#sdk_jars[@]}" >&2
  exit 1
fi
sdk_jar="${sdk_jars[0]}"
src="validation/backup-host-probe/src"
webdav_src="plugins/backup/src/main/java/dev/turboism/plugin/backup/webdav"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 17 -cp "$sdk_jar" -d "$out" \
  "$src/dev/turboism/validation/backup/BackupHostValidationPlugin.java" \
  "$webdav_src/WebDavConfig.java" \
  "$webdav_src/WebDavSyncTarget.java"
cp -r "$src/META-INF" "$out/"

output="$repo_root/build/backup-host-validation-exerciser.jar"
jar cf "$output" -C "$out" .
echo "[probe] $output"
sha256sum "$output"
