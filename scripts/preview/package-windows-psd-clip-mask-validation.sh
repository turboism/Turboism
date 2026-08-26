#!/usr/bin/env bash
# Packages the PSD clip-mask import plugin and its test-only SDK probe into the
# isolated Windows exact-host validation bundle consumed by
# run-cubism-host-validation.sh.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="${TURBOISM_WORKTREE_ID:-main}"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-psd-clip-mask-validation}"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
# Exact artifact of the current :plugins:psd-clip-mask-import:jar task, exported
# by the packagePsdClipMaskHostValidation Gradle task; no glob/version scanning.
plugin_jar="${PSD_CLIP_MASK_PLUGIN_JAR:-}"
test_classes="$repo_root/build/worktree/$worktree_id/integration-tests/classes/java/test"
probe_class_rel="dev/turboism/tests/plugin/WindowsPsdClipMaskValidationProbe.class"
probe_class_dir_rel="dev/turboism/tests/plugin"
probe_descriptor="$repo_root/scripts/preview/windows-psd-clip-mask-validation-plugin.json"
probe_i18n_base="$repo_root/scripts/preview/windows-psd-clip-mask-validation-messages.properties"
plugin_classes="$repo_root/build/worktree/$worktree_id/psd-clip-mask-import/classes/java/main"

if [ ! -f "$agent_jar" ]; then
  printf 'error: preview agent not found: %s\n' "$agent_jar" >&2
  printf 'run: ./gradlew previewBundle :plugins:psd-clip-mask-import:jar :testing:integration-tests:testClasses\n' >&2
  exit 1
fi
[ -n "$plugin_jar" ] || { printf 'error: PSD_CLIP_MASK_PLUGIN_JAR is not set; run packagePsdClipMaskHostValidation (the Gradle task exports the exact :plugins:psd-clip-mask-import:jar archiveFile)\n' >&2; exit 1; }
[ -f "$plugin_jar" ] || { printf 'error: PSD_CLIP_MASK_PLUGIN_JAR is not a file: %s\n' "$plugin_jar" >&2; exit 1; }
[ -f "$test_classes/$probe_class_rel" ] || { printf 'error: validation probe class not found: %s\n' "$test_classes/$probe_class_rel" >&2; exit 1; }
[ -f "$probe_descriptor" ] || { printf 'error: validation descriptor not found: %s\n' "$probe_descriptor" >&2; exit 1; }
[ -f "$probe_i18n_base" ] || { printf 'error: validation probe i18n base catalog not found: %s\n' "$probe_i18n_base" >&2; exit 1; }

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins"
cp "$agent_jar" "$bundle_root/turboism-agent.jar"
cp "$plugin_jar" "$bundle_root/plugins/psd-clip-mask-import.jar"
SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1785456000}" \
  python3 "$repo_root/scripts/release/package-plugin.py" \
  "$plugin_jar" "$bundle_root/psd-clip-mask-import.tplugin"

probe_tmp="$(mktemp -d "$repo_root/build/.psd-clip-mask-probe.XXXXXX")"
trap 'rm -rf "$probe_tmp"' EXIT
mkdir -p "$probe_tmp/$probe_class_dir_rel" "$probe_tmp/META-INF/turboism/i18n" \
  "$probe_tmp/dev/turboism/plugin/psdclipmaskimport"
find "$test_classes/$probe_class_dir_rel" -maxdepth 1 -type f \
  \( -name 'WindowsPsdClipMaskValidationProbe.class' \
     -o -name 'WindowsPsdClipMaskValidationProbe$*.class' \) \
  -exec cp {} "$probe_tmp/$probe_class_dir_rel/" \;
# The probe drives the production planner, but plugin classloaders are isolated per
# jar; ship a probe-local copy of the deterministic planner classes (SDK-only logic)
# so the probe jar stays self-contained.
find "$plugin_classes/dev/turboism/plugin/psdclipmaskimport" -maxdepth 1 -type f \
  \( -name 'PsdClipMaskPlanner.class' \
     -o -name 'PsdClipMaskPlanner$*.class' \
     -o -name 'PsdClipMaskPlan.class' \
     -o -name 'PsdClipMaskPlan$*.class' \) \
  -exec cp {} "$probe_tmp/dev/turboism/plugin/psdclipmaskimport/" \;
cp "$probe_descriptor" "$probe_tmp/META-INF/turboism/plugin.json"
cp "$probe_i18n_base" "$probe_tmp/META-INF/turboism/i18n/messages.properties"
(
  cd "$probe_tmp"
  mapfile -t probe_classes < <(
    find "$probe_class_dir_rel" -maxdepth 1 -type f \
      \( -name 'WindowsPsdClipMaskValidationProbe.class' \
         -o -name 'WindowsPsdClipMaskValidationProbe$*.class' \) \
      -printf '%p\n' | LC_ALL=C sort
  )
  mapfile -t planner_classes < <(
    find "dev/turboism/plugin/psdclipmaskimport" -maxdepth 1 -type f \
      -name '*.class' -printf '%p\n' | LC_ALL=C sort
  )
  [ "${#probe_classes[@]}" -gt 1 ] || {
    printf 'error: validation probe nested classes were not packaged\n' >&2
    exit 1
  }
  [ "${#planner_classes[@]}" -gt 0 ] || {
    printf 'error: validation probe planner classes were not packaged\n' >&2
    exit 1
  }
  jar --create --file "$bundle_root/plugins/psd-clip-mask-validation-probe.jar" \
    "${probe_classes[@]}" \
    "${planner_classes[@]}" \
    META-INF/turboism/plugin.json \
    META-INF/turboism/i18n/messages.properties
)
if jar tf "$bundle_root/plugins/psd-clip-mask-validation-probe.jar" \
  | grep -Eq '\.java$|Test\.class'; then
  printf 'error: validation probe package contains test/source artifacts\n' >&2
  exit 1
fi

(
  cd "$bundle_root"
  sha256sum \
    turboism-agent.jar \
    plugins/psd-clip-mask-import.jar \
    psd-clip-mask-import.tplugin \
    plugins/psd-clip-mask-validation-probe.jar > SHA256SUMS.txt
)

printf '[package] Windows PSD clip-mask validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
