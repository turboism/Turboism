#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local status-bar host validation exerciser plugin JAR against
# the already-built SDK jar. The probe is validation tooling only; it is never
# part of the production preview bundle or product build.
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
src="validation/status-bar-host-probe/src"
catalog='META-INF/turboism/i18n/messages.properties'
if [ ! -f "$src/$catalog" ]; then
  echo "error: declared probe i18n catalog is missing: $src/$catalog" >&2
  exit 1
fi
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 17 -cp "$sdk_jar" -d "$out" \
  "$src/dev/turboism/validation/statusbar/StatusBarHostValidationPlugin.java"
cp -r "$src/META-INF" "$out/"

output="$repo_root/build/status-bar-host-validation-exerciser.jar"
jar cf "$output" -C "$out" .
# Exercise the packaged artifact, not merely the resource source directory.
jar tf "$output" | grep -Fx "$catalog" >/dev/null
echo "[probe] $output"
sha256sum "$output"
