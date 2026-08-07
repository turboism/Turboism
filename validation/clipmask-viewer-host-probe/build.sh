#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local clipmask-viewer host validation exerciser plugin JAR
# against the already-built SDK jar. The probe is validation tooling only; it
# is never part of the production preview bundle or product build.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

sdk_jar="$(ls build/worktree/*/sdk/libs/sdk-*.jar 2>/dev/null | head -1)"
if [ -z "$sdk_jar" ] || [ ! -f "$sdk_jar" ]; then
  echo "error: sdk jar not found; run :sdk:jar first" >&2
  exit 1
fi

base="validation/clipmask-viewer-host-probe"
src="$base/src"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 17 -cp "$sdk_jar" -d "$out" \
  "$src/dev/turboism/validation/clipmaskviewer/ClipMaskViewerHostValidationPlugin.java"
cp -r "$src/META-INF" "$out/"

output="$repo_root/build/clipmask-viewer-validation-exerciser.jar"
jar cf "$output" -C "$out" .
if ! jar tf "$output" | grep -Fq 'META-INF/turboism/plugin.json'; then
  echo "error: probe jar is missing META-INF/turboism/plugin.json" >&2
  exit 1
fi
echo "[probe] $output"
sha256sum "$output"
