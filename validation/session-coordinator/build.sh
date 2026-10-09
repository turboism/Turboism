#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local multi-probe session coordinator plugin JAR against the
# already-built SDK jar. The coordinator is validation tooling only; it is never
# part of the production preview bundle or product build.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
sdk_dir="$repo_root/build/worktree/$worktree_id/sdk/libs"
shopt -s nullglob
sdk_jars=("$sdk_dir"/sdk-*.jar)
if [ "${#sdk_jars[@]}" -ne 1 ] || [ ! -f "${sdk_jars[0]}" ]; then
  echo "error: expected exactly one sdk jar in $sdk_dir; run :sdk:jar first" >&2
  exit 1
fi
sdk_jar="${sdk_jars[0]}"

src="validation/session-coordinator/src"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 17 -cp "$sdk_jar" -d "$out" \
  "validation/shared/src/dev/turboism/validation/NativeFreeStartup.java" \
  "$src/dev/turboism/validation/session/SessionPlan.java" \
  "$src/dev/turboism/validation/session/SessionRunner.java" \
  "$src/dev/turboism/validation/session/SessionCoordinatorPlugin.java"
cp -r "$src/META-INF" "$out/"

output="$repo_root/build/session-coordinator.jar"
jar cf "$output" -C "$out" .
jar tf "$output" | grep -Fq 'META-INF/turboism/plugin.json' \
  || { echo "error: coordinator jar is missing META-INF/turboism/plugin.json" >&2; exit 1; }
echo "[probe] $output"
sha256sum "$output"
