#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local edit-session host-validation probe jar. The probe is
# validation tooling only and is never part of the production preview bundle.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
sdk_jar="$(find "build/worktree/$worktree_id/sdk/libs" -maxdepth 1 -name 'sdk-*.jar' -type f | head -1)"
if [ -z "$sdk_jar" ] || [ ! -f "$sdk_jar" ]; then
  echo "error: sdk jar not found; run :sdk:jar first" >&2
  exit 1
fi

base="validation/edit-host-probe"
src="$base/src"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 17 -cp "$sdk_jar" -d "$out" \
  "$src/dev/turboism/validation/edithost/EditHostValidationPlugin.java"
cp -r "$src/META-INF" "$out/"

output="$repo_root/build/edit-host-validation-probe.jar"
jar cf "$output" -C "$out" .
if ! jar tf "$output" | grep -Fq 'META-INF/turboism/plugin.json'; then
  echo "error: edit probe jar is missing META-INF/turboism/plugin.json" >&2
  exit 1
fi
echo "[probe] $output"
sha256sum "$output"
