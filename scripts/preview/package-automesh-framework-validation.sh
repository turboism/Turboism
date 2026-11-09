#!/usr/bin/env bash
# Package the Auto Mesh framework exact-host validation bundle.
#
# The bundle carries the preview agent, the Turbo Mesh plugin that registers the probed
# algorithms, and a test-only probe jar. The probe is never shipped as a production plugin.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/automesh-framework-validation}"
"$repo_root/gradlew" previewBundle :plugins:turbo-mesh:jar :testing:integration-tests:testClasses >/dev/null
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
turbo_mesh_jars=("$repo_root"/build/worktree/"$worktree_id"/turbo-mesh/libs/turbo-mesh-*-"$worktree_id".jar)
[ "${#turbo_mesh_jars[@]}" -eq 1 ] && [ -f "${turbo_mesh_jars[0]}" ] || {
  printf 'error: expected exactly one turbo-mesh jar under build/worktree/%s/turbo-mesh/libs/\n' "$worktree_id" >&2
  exit 1
}
turbo_mesh_jar="${turbo_mesh_jars[0]}"
test_classes="$repo_root/build/worktree/$worktree_id/integration-tests/classes/java/test"
class_dir="dev/turboism/tests/plugin"
descriptor="$repo_root/scripts/preview/automesh-framework-validation-plugin.json"
# The probe and its host-reflection helpers are one package-private family, so they ship
# together. WindowsHistoryNativeUiHostClose is the shared audited close path.
probe_classes=(
  AutoMeshFrameworkValidationProbe
  AutoMeshGeometryDigest
  AutoMeshDumpWriter
  AutoMeshProbeResult
  AutoMeshProbeStatus
  AutoMeshProbeBudget
  AutoMeshProbeReadiness
  AutoMeshHostVersionCode
  AutoMeshAlphaCoverage
  NativeAutoMeshFunnel
  NativeAutoMeshPopup
  WindowsHistoryNativeUiHostClose
  WindowsProbeHarness
  WindowsHistoryManagerValidationProbe
)

for required in "$agent_jar" "$turbo_mesh_jar" "$descriptor"; do
  [ -f "$required" ] || { printf 'error: required artifact not found: %s\n' "$required" >&2; exit 1; }
done
for probe_class in "${probe_classes[@]}"; do
  required="$test_classes/$class_dir/$probe_class.class"
  [ -f "$required" ] || { printf 'error: required probe class not found: %s\n' "$required" >&2; exit 1; }
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

probe_tmp="$(mktemp -d "$repo_root/build/.automesh-probe.XXXXXX")"
trap 'rm -rf "$probe_tmp"' EXIT
mkdir -p "$probe_tmp/$class_dir" "$probe_tmp/META-INF/turboism/i18n"
packaged=()
for probe_class in "${probe_classes[@]}"; do
  while IFS= read -r class_file; do
    cp "$class_file" "$probe_tmp/$class_dir/"
    packaged+=("$class_dir/$(basename "$class_file")")
  done < <(find "$test_classes/$class_dir" -maxdepth 1 -type f \
    \( -name "$probe_class.class" -o -name "$probe_class\$*.class" \) -print | LC_ALL=C sort)
done
[ "${#packaged[@]}" -gt "${#probe_classes[@]}" ] \
  || { printf 'error: probe nested classes were not packaged\n' >&2; exit 1; }
cp "$descriptor" "$probe_tmp/META-INF/turboism/plugin.json"
printf '%s\n' \
  'plugin.name=Auto Mesh Framework Validation Probe' \
  'plugin.description=Manual-test-only Auto Mesh framework validation.' \
  > "$probe_tmp/META-INF/turboism/i18n/messages.properties"
(
  cd "$probe_tmp"
  mapfile -t sorted < <(printf '%s\n' "${packaged[@]}" | LC_ALL=C sort)
  jar --create --file "$bundle_root/plugins/automesh-framework-validation-probe.jar" \
    "${sorted[@]}" META-INF/turboism/plugin.json META-INF/turboism/i18n/messages.properties
)
if jar tf "$bundle_root/plugins/automesh-framework-validation-probe.jar" \
  | grep -Eq 'Test\.class|\.java$'; then
  printf 'error: probe package contains test/source artifacts\n' >&2
  exit 1
fi
if ! jar tf "$bundle_root/plugins/automesh-framework-validation-probe.jar" \
  | grep -Fxq 'META-INF/turboism/i18n/messages.properties'; then
  printf 'error: probe package is missing its declared base i18n catalog\n' >&2
  exit 1
fi
(
  cd "$bundle_root"
  sha256sum turboism-agent.jar plugins/turbo-mesh.jar \
    plugins/automesh-framework-validation-probe.jar home-config.json > SHA256SUMS.txt
)
printf '[package] auto mesh framework validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
