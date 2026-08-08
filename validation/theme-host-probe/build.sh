#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local theme host validation exerciser plugin JAR against the
# already-built SDK jar. The probe is validation tooling only; it is never part
# of the production preview bundle or product build.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

sdk_jar="$(ls build/worktree/*/sdk/libs/sdk-*.jar 2>/dev/null | head -1)"
if [ -z "$sdk_jar" ] || [ ! -f "$sdk_jar" ]; then
  echo "error: sdk jar not found; run :sdk:jar first" >&2
  exit 1
fi

src="validation/theme-host-probe/src"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 17 -cp "$sdk_jar" -d "$out" \
  "$src/dev/turboism/validation/theme/ThemeHostValidationPlugin.java"
cp -r "$src/META-INF" "$out/"

output="$repo_root/build/theme-host-validation-exerciser.jar"
jar cf "$output" -C "$out" .
echo "[probe] $output"
sha256sum "$output"
