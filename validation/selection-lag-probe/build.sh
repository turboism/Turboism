#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local selection-lag host diagnostic probe plugin JAR
# against the already-built SDK jar. The probe is validation tooling only; it
# is never part of the production preview bundle or product build.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
sdk_jar="$(find "build/worktree/$worktree_id/sdk/libs" -maxdepth 1 -name 'sdk-*.jar' -type f 2>/dev/null | head -1)"
if [ -z "$sdk_jar" ] || [ ! -f "$sdk_jar" ]; then
  sdk_jar="$(ls build/worktree/*/sdk/libs/sdk-*.jar 2>/dev/null | head -1)"
fi
if [ -z "$sdk_jar" ] || [ ! -f "$sdk_jar" ]; then
  echo "error: sdk jar not found; run :sdk:jar first" >&2
  exit 1
fi

base="validation/selection-lag-probe"
src="$base/src"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 17 -cp "$sdk_jar" -d "$out" \
  "$src/dev/turboism/validation/selectionlag/SelectionLagProbePlugin.java"
test_out="$(mktemp -d)"
trap 'rm -rf "$out" "$test_out"' EXIT
javac --release 17 -cp "$sdk_jar:$out" -d "$test_out" \
  "$base/test/dev/turboism/validation/selectionlag/SelectionLagProbePluginTest.java"
java -cp "$sdk_jar:$out:$test_out" \
  dev.turboism.validation.selectionlag.SelectionLagProbePluginTest
cp -r "$src/META-INF" "$out/"

output="$repo_root/build/selection-lag-probe.jar"
jar cf "$output" -C "$out" .
if ! jar tf "$output" | grep -Fq 'META-INF/turboism/plugin.json'; then
  echo "error: probe jar is missing META-INF/turboism/plugin.json" >&2
  exit 1
fi
if jar tf "$output" | grep -Eq 'SelectionLagProbePlugin\$EdtSampler.class'; then
  : # nested classes are expected (EdtSampler, records)
fi
if jar tf "$output" | grep -Eq '\.java$'; then
  echo "error: probe jar contains source artifacts" >&2
  exit 1
fi
echo "[probe] $output"
sha256sum "$output"
