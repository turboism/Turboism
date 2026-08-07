#!/usr/bin/env bash
# Package the mesh mirror-axis exact-host validation bundle.
# Usage: scripts/preview/package-mesh-mirror-axis-validation.sh [bundle-dir]
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="${TURBOISM_WORKTREE_ID:-main}"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/mesh-mirror-axis-validation}"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
mesh_jar="$repo_root/build/worktree/$worktree_id/mesh/libs/mesh-0.1.0-SNAPSHOT-$worktree_id.jar"
test_classes="$repo_root/build/worktree/$worktree_id/tests/classes/java/test"
probe_class_rel="dev/turboism/tests/plugin"
probe_descriptor="$repo_root/scripts/preview/mesh-mirror-axis-validation-plugin.json"

for required in "$agent_jar" "$mesh_jar" "$test_classes/$probe_class_rel/WindowsMeshMirrorAxisValidationProbe.class" "$probe_descriptor"; do
  if [ ! -f "$required" ]; then
    printf 'error: required artifact not found: %s\n' "$required" >&2
    exit 1
  fi
done

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/logs" "$bundle_root/state" "$bundle_root/plugin-data"
cp "$agent_jar" "$bundle_root/turboism-agent.jar"
cp "$mesh_jar" "$bundle_root/plugins/mesh.jar"

probe_tmp="$(mktemp -d "$repo_root/build/.mesh-mirror-probe.XXXXXX")"
trap 'rm -rf "$probe_tmp"' EXIT
mkdir -p "$probe_tmp/$probe_class_rel" "$probe_tmp/META-INF/turboism"
find "$test_classes/$probe_class_rel" -maxdepth 1 -type f \
  \( -name 'WindowsMeshMirrorAxisValidationProbe.class' \
     -o -name 'WindowsMeshMirrorAxisValidationProbe$*.class' \) \
  -exec cp {} "$probe_tmp/$probe_class_rel/" \;
cp "$probe_descriptor" "$probe_tmp/META-INF/turboism/plugin.json"
(
  cd "$probe_tmp"
  jar --create --file "$bundle_root/plugins/mesh-mirror-axis-validation-probe.jar" \
    "$probe_class_rel/WindowsMeshMirrorAxisValidationProbe.class" \
    "$probe_class_rel/WindowsMeshMirrorAxisValidationProbe"*.class \
    META-INF/turboism/plugin.json
)
if jar tf "$bundle_root/plugins/mesh-mirror-axis-validation-probe.jar" \
  | grep -Eq 'WindowsMeshMirrorAxisValidationProbeTest|\.java$'; then
  printf 'error: probe package contains test/source artifacts\n' >&2
  exit 1
fi

printf 'bundle: %s\n' "$bundle_root"
printf '  turboism-agent.jar          %s bytes\n' "$(stat -c %s "$bundle_root/turboism-agent.jar")"
printf '  plugins/mesh.jar            %s bytes\n' "$(stat -c %s "$bundle_root/plugins/mesh.jar")"
printf '  plugins/mesh-mirror-axis-validation-probe.jar  %s bytes\n' \
  "$(stat -c %s "$bundle_root/plugins/mesh-mirror-axis-validation-probe.jar")"
