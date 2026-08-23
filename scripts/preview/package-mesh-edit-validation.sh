#!/usr/bin/env bash
# Package the direct mesh edit exact-host validation bundle.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/mesh-edit-validation}"
"$repo_root/gradlew" previewBundle >/dev/null
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
mesh_enhance_jars=("$repo_root"/build/worktree/"$worktree_id"/mesh-edit-mirror-axis-enhance/libs/mesh-edit-mirror-axis-enhance-*-$worktree_id.jar)
[ "${#mesh_enhance_jars[@]}" -eq 1 ] && [ -f "${mesh_enhance_jars[0]}" ] || {
  printf 'error: expected exactly one mirror-axis enhancement jar under build/worktree/%s/mesh-edit-mirror-axis-enhance/libs/\n' "$worktree_id" >&2
  exit 1
}
mesh_enhance_jar="${mesh_enhance_jars[0]}"
test_classes="$repo_root/build/worktree/$worktree_id/tests/classes/java/test"
class_dir="dev/turboism/tests/plugin"
main_class="WindowsMeshEditValidationProbe"
descriptor="$repo_root/scripts/preview/mesh-edit-validation-plugin.json"

for required in "$agent_jar" "$mesh_enhance_jar" "$test_classes/$class_dir/$main_class.class" "$descriptor"; do
  [ -f "$required" ] || { printf 'error: required artifact not found: %s\n' "$required" >&2; exit 1; }
done

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/logs" "$bundle_root/state" "$bundle_root/plugin-data"
cp "$agent_jar" "$bundle_root/turboism-agent.jar"
cp "$mesh_enhance_jar" "$bundle_root/plugins/mesh-edit-mirror-axis-enhance.jar"
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

probe_tmp="$(mktemp -d "$repo_root/build/.mesh-edit-probe.XXXXXX")"
trap 'rm -rf "$probe_tmp"' EXIT
mkdir -p "$probe_tmp/$class_dir" "$probe_tmp/META-INF/turboism/i18n"
find "$test_classes/$class_dir" -maxdepth 1 -type f \
  \( -name "$main_class.class" -o -name "$main_class\$*.class" \) \
  -exec cp {} "$probe_tmp/$class_dir/" \;
cp "$descriptor" "$probe_tmp/META-INF/turboism/plugin.json"
printf '%s\n' \
  'plugin.name=Windows Mesh Edit Validation Probe' \
  'plugin.description=Manual-test-only direct mesh edit validation.' \
  > "$probe_tmp/META-INF/turboism/i18n/messages.properties"
(
  cd "$probe_tmp"
  mapfile -t classes < <(find "$class_dir" -maxdepth 1 -type f -name "$main_class*.class" -printf '%p\n' | LC_ALL=C sort)
  [ "${#classes[@]}" -gt 1 ] || { printf 'error: validation probe nested classes were not packaged\n' >&2; exit 1; }
  jar --create --file "$bundle_root/plugins/mesh-edit-validation-probe.jar" \
    "${classes[@]}" META-INF/turboism/plugin.json META-INF/turboism/i18n/messages.properties
)
if jar tf "$bundle_root/plugins/mesh-edit-validation-probe.jar" | grep -Eq 'WindowsMeshEditValidationProbeTest|\.java$'; then
  printf 'error: probe package contains test/source artifacts\n' >&2
  exit 1
fi
if ! jar tf "$bundle_root/plugins/mesh-edit-validation-probe.jar" \
  | grep -Fxq 'META-INF/turboism/i18n/messages.properties'; then
  printf 'error: probe package is missing its declared base i18n catalog\n' >&2
  exit 1
fi
(
  cd "$bundle_root"
  sha256sum turboism-agent.jar plugins/mesh-edit-mirror-axis-enhance.jar plugins/mesh-edit-validation-probe.jar home-config.json > SHA256SUMS.txt
)
printf '[package] mesh edit validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
