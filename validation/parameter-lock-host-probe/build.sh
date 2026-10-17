#!/usr/bin/env bash
set -euo pipefail

# Builds the task-local parameter-lock host validation probe plugin
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
base="validation/parameter-lock-host-probe"
src="$base/src"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 17 -cp "$sdk_jar" -d "$out" \
  "$src/dev/turboism/validation/parameterlock/ParameterLockHostValidationPlugin.java"
cp -r "$src/META-INF" "$out/"

output="$repo_root/build/parameter-lock-host-validation-probe.jar"
mkdir -p "$(dirname "$output")"
jar cf "$output" -C "$out" .

# Lightweight manifest preflight sanity check (full runtime schema verification is executed separately by root agent)
python3 - <<EOF
import json
import sys
import zipfile

jar_path = "$output"
with zipfile.ZipFile(jar_path, 'r') as zf:
    names = set(zf.namelist())
    if "META-INF/turboism/plugin.json" not in names:
        print("error: probe jar missing META-INF/turboism/plugin.json", file=sys.stderr)
        sys.exit(1)

    with zf.open("META-INF/turboism/plugin.json") as f:
        meta = json.load(f)

    # Basic structure check
    assert meta.get("format") == "turboism.plugin.meta", f"Invalid format: {meta.get('format')}"
    assert meta.get("schemaVersion") == 3, f"Invalid schemaVersion: {meta.get('schemaVersion')}"
    assert meta.get("id") == "dev.turboism.validation.parameter-lock", f"Invalid id: {meta.get('id')}"

    # Validate entrypoints
    entrypoints = meta.get("entrypoints", [])
    assert len(entrypoints) > 0, "No entrypoints defined"
    for ep in entrypoints:
        class_entry = ep.replace(".", "/") + ".class"
        assert class_entry in names, f"Entrypoint class {ep} ({class_entry}) not found in JAR"

    # Validate permissions & capabilities
    assert meta.get("permissions"), "Permissions must not be empty"
    assert meta.get("capabilities"), "Capabilities must not be empty"

    # Validate environment
    env = meta.get("environment", {})
    assert env.get("requiresCubism") is True, "requiresCubism must be true"
    assert env.get("ui") == "embedded", "ui must be embedded"

print(f"[manifest-preflight] PASS: {meta['id']} v{meta.get('version')} ({len(entrypoints)} entrypoint verified in JAR)")
EOF

echo "[probe] $output"
sha256sum "$output"
