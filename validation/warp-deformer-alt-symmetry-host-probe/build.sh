#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local Warp deformer Alt-symmetry reconnaissance probe against the
# already-built SDK jar, runs the offline mirror-measurement self-check against the
# compiled classes, and gates the packaged JAR content. The probe is validation
# tooling only: it is never part of the production preview bundle or product build,
# and its self-check classes are never packaged into the plugin JAR.
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

base="validation/warp-deformer-alt-symmetry-host-probe"
plugin_pkg="dev/turboism/validation/warpaltsymmetry"
catalog='META-INF/turboism/i18n/messages.properties'
if [ ! -f "$base/src/$catalog" ]; then
  echo "error: declared probe i18n catalog is missing: $base/src/$catalog" >&2
  exit 1
fi

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

# 1. Compile the plugin against the SDK.
javac --release 17 -cp "$sdk_jar" -d "$out/plugin" \
  "$base/src/$plugin_pkg/WarpDeformerAltSymmetryHostValidationPlugin.java"

# 2. Compile and run the offline self-check against the compiled plugin classes.
javac --release 17 -cp "$sdk_jar:$out/plugin" -d "$out/selfcheck" \
  "$base/selfcheck/$plugin_pkg/WarpAltSymmetrySelfCheck.java"
java -cp "$sdk_jar:$out/plugin:$out/selfcheck" \
  dev.turboism.validation.warpaltsymmetry.WarpAltSymmetrySelfCheck

# 3. Stage the plugin metadata and assemble the validation-only JAR.
cp -r "$base/src/META-INF" "$out/plugin/"
output="$repo_root/build/warp-deformer-alt-symmetry-host-validation-probe.jar"
jar cf "$output" -C "$out/plugin" .

# 4. JAR content contract gate.
status=0
for entry in \
  META-INF/turboism/plugin.json \
  "$catalog" \
  "$plugin_pkg/WarpDeformerAltSymmetryHostValidationPlugin.class"
do
  if ! jar tf "$output" | grep -Fqx "$entry"; then
    echo "error: probe jar is missing $entry" >&2
    status=1
  fi
done
if jar tf "$output" | grep -Fq 'WarpAltSymmetrySelfCheck'; then
  echo "error: probe jar must not contain self-check classes" >&2
  status=1
fi
if jar tf "$output" | grep -Fq 'com/live2d/'; then
  echo "error: probe jar must not contain host classes" >&2
  status=1
fi
if [ "$status" != 0 ]; then
  exit 1
fi
echo "[probe] $output"
sha256sum "$output"
