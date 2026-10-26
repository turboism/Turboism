#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local ACP lifecycle probe and the shared test-only UI close
# helper. None of the helper classes are production SDK/runtime dependencies.
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
sdk_jar="$(find "build/worktree/$worktree_id/sdk/libs" -maxdepth 1 -name 'sdk-*.jar' -type f | head -1)"
if [ -z "$sdk_jar" ] || [ ! -f "$sdk_jar" ]; then
  echo "error: sdk jar not found; run :sdk:jar first" >&2
  exit 1
fi
sdk_jar="$repo_root/$sdk_jar"

base="validation/acp-host-probe"
src="$base/src"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

helper_root="testing/integration-tests/src/test/java/dev/turboism/tests/plugin"
javac --release 17 -cp "$sdk_jar" -d "$out" \
  "$helper_root/WindowsProbeHarness.java" \
  "$helper_root/WindowsHistoryManagerValidationProbe.java" \
  "$helper_root/WindowsHistoryNativeUiHostClose.java" \
  "$helper_root/McpValidationHostClose.java" \
  "$src/dev/turboism/validation/acp/AcpHostValidationPlugin.java" \
  "$src/dev/turboism/validation/acp/AcpRenderUiEvidence.java"
cp -r "$src/META-INF" "$out/"
python3 "$repo_root/scripts/materialize_verification_records.py"
mkdir -p "$out/META-INF/turboism/verification"
for record in "$repo_root"/build/generated/verification/cubism-*-editor-model.json; do
  cp "$record" \
    "$out/META-INF/turboism/verification/"
done

output="$repo_root/build/acp-host-validation-probe.jar"
jar cf "$output" -C "$out" .
if ! jar tf "$output" | grep -Fq 'META-INF/turboism/plugin.json'; then
  echo "error: ACP probe jar is missing META-INF/turboism/plugin.json" >&2
  exit 1
fi
echo "[probe] $output"
sha256sum "$output"


if [ "${1:-}" = "selftest" ]; then
  javac --release 17 -cp "$out" -d "$out" "$base/selftest/AcpRenderUiEvidenceSelfTest.java"
  java -Djava.awt.headless=true -cp "$out" dev.turboism.validation.acp.AcpRenderUiEvidenceSelfTest
fi
