#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local parameter-batch-transfer host validation probe plugin
# JAR against the already-built SDK jar. The probe is validation tooling only;
# it is never part of the production preview bundle or product build.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
sdk_dir="$repo_root/build/worktree/$worktree_id/sdk/libs"
shopt -s nullglob
sdk_jar="$(ls -t "$sdk_dir"/sdk-*.jar 2>/dev/null | head -1)"
if [ -z "$sdk_jar" ] || [ ! -f "$sdk_jar" ]; then
  echo "error: no sdk jar found in $sdk_dir" >&2
  exit 1
fi
base="validation/parameter-batch-transfer-host-probe"
src="$base/src"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 17 -cp "$sdk_jar" -d "$out" \
  "$src/dev/turboism/validation/parameterbatchtransfer/ParameterBatchTransferHostValidationPlugin.java"
cp -r "$src/META-INF" "$out/"

output="$repo_root/build/parameter-batch-transfer-host-validation-probe.jar"
jar cf "$output" -C "$out" .
if ! jar tf "$output" | grep -Fq 'META-INF/turboism/plugin.json'; then
  echo "error: probe jar is missing META-INF/turboism/plugin.json" >&2
  exit 1
fi
echo "[probe] $output"
sha256sum "$output"
