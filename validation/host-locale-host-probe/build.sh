#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local host-locale host validation exerciser plugin JAR
# against the already-built SDK jar. The probe is validation tooling only; it
# is never part of the production preview bundle or product build.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
sdk_dir="$repo_root/build/worktree/$worktree_id/sdk/libs"
shopt -s nullglob
sdk_jars=("$sdk_dir"/sdk-*.jar)
if [ "${#sdk_jars[@]}" -lt 1 ]; then
  echo "error: no sdk jar found in $sdk_dir; run :sdk:jar first" >&2
  exit 1
fi
# pick the newest sdk jar; stale versioned jars may linger in the worktree
sdk_jar="$(ls -t "${sdk_jars[@]}" | head -1)"
src="validation/host-locale-host-probe/src"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 17 -cp "$sdk_jar" -d "$out" \
  "$src/dev/turboism/validation/hostlocale/HostLocaleHostValidationPlugin.java"
cp -r "$src/META-INF" "$out/"

output="$repo_root/build/host-locale-validation-exerciser.jar"
jar cf "$output" -C "$out" .
if ! jar tf "$output" | grep -Fq 'META-INF/turboism/plugin.json'; then
  echo "error: probe jar is missing META-INF/turboism/plugin.json" >&2
  exit 1
fi
echo "[probe] $output"
sha256sum "$output"
