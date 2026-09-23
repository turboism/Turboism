#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local FPS counting host validation exerciser plugin JAR
# against the already-built SDK jar. The probe is validation tooling only; it
# is never part of the production preview bundle or product build.
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
src="validation/fps-host-probe/src"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

mkdir -p "$out/actor" "$out/observer"
helpers="testing/integration-tests/src/test/java/dev/turboism/tests/plugin"
javac --release 17 -cp "$sdk_jar" -d "$out/actor" \
  "$src/dev/turboism/validation/fps/FpsHostValidationPlugin.java" \
  "$src/dev/turboism/validation/fps/FpsLifecycleAcceptance.java" \
  "$helpers/McpValidationHostClose.java" \
  "$helpers/WindowsHistoryNativeUiHostClose.java"
cp -r "$src/META-INF" "$out/actor/"

output="$repo_root/build/fps-host-validation-exerciser.jar"
jar cf "$output" -C "$out/actor" .
echo "[probe] $output"
sha256sum "$output"

observer="validation/fps-host-probe/observer"
javac --release 17 -cp "$sdk_jar" -d "$out/observer" \
  "$observer/dev/turboism/validation/fpsobserver/FpsLifecycleObserverPlugin.java"
cp -r "$observer/META-INF" "$out/observer/"
observer_output="$repo_root/build/fps-host-validation-observer.jar"
jar cf "$observer_output" -C "$out/observer" .
echo "[probe] $observer_output"
sha256sum "$observer_output"
