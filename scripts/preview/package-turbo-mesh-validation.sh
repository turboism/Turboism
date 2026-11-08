#!/usr/bin/env bash
# Package the turbo-mesh custom placement exact-host validation bundle.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/turbo-mesh-validation}"
"$repo_root/gradlew" previewBundle >/dev/null
"$repo_root/gradlew" :plugins:turbo-mesh:jar :testing:integration-tests:compileTestJava >/dev/null
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
turbo_mesh_jars=("$repo_root"/build/worktree/"$worktree_id"/turbo-mesh/libs/turbo-mesh-*-$worktree_id.jar)
[ "${#turbo_mesh_jars[@]}" -eq 1 ] && [ -f "${turbo_mesh_jars[0]}" ] || {
  printf 'error: expected exactly one turbo-mesh jar under build/worktree/%s/turbo-mesh/libs/\n' "$worktree_id" >&2
  exit 1
}
turbo_mesh_jar="${turbo_mesh_jars[0]}"
test_classes="$repo_root/build/worktree/$worktree_id/integration-tests/classes/java/test"
plugin_classes="$repo_root/build/worktree/$worktree_id/turbo-mesh/classes/java/main"
class_dir="dev/turboism/tests/plugin"
domain_dir="dev/turboism/plugin/turbomesh"
main_class="WindowsTurboMeshValidationProbe"
descriptor="$repo_root/scripts/preview/turbo-mesh-validation-plugin.json"

for required in "$agent_jar" "$turbo_mesh_jar" "$test_classes/$class_dir/$main_class.class" "$descriptor"; do
  [ -f "$required" ] || { printf 'error: required artifact not found: %s\n' "$required" >&2; exit 1; }
done

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/logs" "$bundle_root/state" "$bundle_root/plugin-data"
cp "$agent_jar" "$bundle_root/turboism-agent.jar"
cp "$turbo_mesh_jar" "$bundle_root/plugins/turbo-mesh.jar"
cat > "$bundle_root/home-config.json" <<JSON
{
  "format": "turboism.runtime.config",
  "schemaVersion": 1,
  "worktreeId": "$worktree_id",
  "pluginDirs": ["plugins/"],
  "logLevel": "INFO",
  "maxLogStorageMiB": 100,
  "safeMode": false,
  "hooks": {
    "disabledIds": [],
    "denylistedClasses": [],
    "startup": { "skipUpdateCheck": true, "skipSplash": true, "skipInformation": true }
  }
}
JSON

# Plugin classes are classloader-isolated per plugin jar, so the probe jar carries the
# turbo-mesh domain classes alongside the probe itself.
probe_tmp="$(mktemp -d "$repo_root/build/.turbo-mesh-probe.XXXXXX")"
trap 'rm -rf "$probe_tmp"' EXIT
mkdir -p "$probe_tmp/$class_dir" "$probe_tmp/dev/turboism/plugin" "$probe_tmp/META-INF/turboism/i18n"
find "$test_classes/$class_dir" -maxdepth 1 -type f \
  \( -name "$main_class.class" -o -name "$main_class\$*.class" \) \
  -exec cp {} "$probe_tmp/$class_dir/" \;
cp -r "$plugin_classes/$domain_dir" "$probe_tmp/dev/turboism/plugin/"
cp "$descriptor" "$probe_tmp/META-INF/turboism/plugin.json"
printf '%s\n' \
  'plugin.name=Windows Turbo Mesh Validation Probe' \
  'plugin.description=Manual-test-only custom ArtMesh placement validation.' \
  > "$probe_tmp/META-INF/turboism/i18n/messages.properties"
(
  cd "$probe_tmp"
  jar --create --file "$bundle_root/plugins/turbo-mesh-validation-probe.jar" \
    dev META-INF/turboism/plugin.json META-INF/turboism/i18n/messages.properties
)
if jar tf "$bundle_root/plugins/turbo-mesh-validation-probe.jar" | grep -Eq 'Test\.class|\.java$'; then
  printf 'error: probe package contains test/source artifacts\n' >&2
  exit 1
fi
if ! jar tf "$bundle_root/plugins/turbo-mesh-validation-probe.jar" \
  | grep -Fxq 'META-INF/turboism/i18n/messages.properties'; then
  printf 'error: probe package is missing its declared base i18n catalog\n' >&2
  exit 1
fi
(
  cd "$bundle_root"
  sha256sum turboism-agent.jar plugins/turbo-mesh.jar plugins/turbo-mesh-validation-probe.jar home-config.json > SHA256SUMS.txt
)
printf '[package] turbo mesh validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
