#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local MCP lifecycle probe and shared test-only UI close helper.
# None of the helper classes are production SDK/runtime dependencies.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
sdk_jar="$(find "build/worktree/$worktree_id/sdk/libs" -maxdepth 1 -name 'sdk-*.jar' -type f | head -1)"
if [ -z "$sdk_jar" ] || [ ! -f "$sdk_jar" ]; then
  echo "error: sdk jar not found; run :sdk:jar first" >&2
  exit 1
fi

base="validation/mcp-host-probe"
src="$base/src"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

helper_root="testing/integration-tests/src/test/java/dev/turboism/tests/plugin"
javac --release 17 -cp "$sdk_jar" -d "$out" \
  "validation/shared/src/dev/turboism/validation/NativeFreeStartup.java" \
  "validation/shared/src/dev/turboism/validation/SessionGate.java" \
  "$helper_root/WindowsHistoryNativeUiHostClose.java" \
  "$helper_root/McpValidationHostClose.java" \
  "$helper_root/McpTexturePersistenceProbe.java" \
  "$src/dev/turboism/validation/mcp/CoreOwnedMocProbe.java" \
  "$src/dev/turboism/validation/mcp/McpHostValidationPlugin.java"
cp -r "$src/META-INF" "$out/"
mkdir -p "$out/META-INF/turboism/verification"
for record in "$repo_root"/compatibility/cubism/verification/cubism-*-editor-model.json; do
  cp "$record" \
    "$out/META-INF/turboism/verification/"
done

if [ -n "${TURBOISM_VALIDATION_MOC_FIXTURE:-}" ]; then
  moc_hash="$(sha256sum "$TURBOISM_VALIDATION_MOC_FIXTURE" | cut -d ' ' -f 1)"
  [ "$moc_hash" = 'aa59f7a3dc3b30b23b5f10afd9192ed755e4d01dc9a12442d99b972a418e2543' ] \
    || { echo 'error: official MOC fixture hash mismatch' >&2; exit 1; }
  mkdir -p "$out/validation/moc"
  cp "$TURBOISM_VALIDATION_MOC_FIXTURE" "$out/validation/moc/Haru.moc3"
  cp "$base/moc-fixture-source.json" "$out/validation/moc/source.json"
  python3 - "$out/META-INF/turboism/plugin.json" <<'PY'
import json
from pathlib import Path
import sys

path = Path(sys.argv[1])
descriptor = json.loads(path.read_text())
descriptor["resources"].append("validation/moc/")
path.write_text(json.dumps(descriptor, indent=2) + "\n")
PY
fi

output="$repo_root/build/mcp-host-validation-probe.jar"
jar cf "$output" -C "$out" .
if ! jar tf "$output" | grep -Fq 'META-INF/turboism/plugin.json'; then
  echo "error: MCP probe jar is missing META-INF/turboism/plugin.json" >&2
  exit 1
fi
echo "[probe] $output"
sha256sum "$output"
