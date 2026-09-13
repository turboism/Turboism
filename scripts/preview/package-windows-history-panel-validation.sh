#!/usr/bin/env bash
set -euo pipefail
shopt -s nullglob

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="${TURBOISM_WORKTREE_ID:-$(scripts/dev/worktree-id.sh)}"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-history-panel-validation}"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
panel_jars=("$repo_root"/build/worktree/"$worktree_id"/history-panel/libs/history-panel-*-$worktree_id.jar)
test_classes="$repo_root/build/worktree/$worktree_id/integration-tests/classes/java/test"
probe_class_dir="dev/turboism/tests/plugin"
probe_class="WindowsHistoryManagerValidationProbe"
probe_descriptor="$repo_root/scripts/preview/windows-history-manager-validation-plugin.json"
seed_class="WindowsHistorySeedValidationProbe"
float_class="WindowsHistoryFloatProbe"
float_descriptor="$repo_root/scripts/preview/windows-history-float-plugin.json"
seed_descriptor="$repo_root/scripts/preview/windows-history-seed-validation-plugin.json"
native_ui_class="WindowsHistoryNativeUiIngressProbe"
native_ui_descriptor="$repo_root/scripts/preview/windows-history-native-ui-plugin.json"
# The native-UI probe's Parts-tree actor reuses the mesh probe's structural tree
# selection, so its classes travel inside the probe jar too.
mesh_edit_class="WindowsMeshEditValidationProbe"
launcher="$repo_root/scripts/preview/launch-cubism-history-validation.ps1"

[ -f "$agent_jar" ] || { printf 'error: run ./gradlew previewBundle :plugins:history-panel:jar :testing:integration-tests:testClasses first\n' >&2; exit 1; }
[ "${#panel_jars[@]}" -eq 1 ] && [ -f "${panel_jars[0]}" ] || {
  printf 'error: expected exactly one history-panel plugin jar under build/worktree/%s/history-panel/libs/\n' "$worktree_id" >&2
  exit 1
}
panel_jar="${panel_jars[0]}"
[ -f "$test_classes/$probe_class_dir/$seed_class.class" ] || { printf 'error: seed class missing\n' >&2; exit 1; }

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/logs" "$bundle_root/state"
cp "$agent_jar" "$bundle_root/turboism-agent.jar"
cp "$panel_jar" "$bundle_root/plugins/history-panel.jar"
cp "$launcher" "$bundle_root/"
cp "$repo_root/scripts/preview/run-history-validation.bat" "$bundle_root/"
cp "$repo_root/scripts/preview/README-history-validation.md" "$bundle_root/README.md"
cp "$repo_root/scripts/preview/README-history-native-ui-validation.md" \
  "$bundle_root/README-native-ui-validation.md"
cat > "$bundle_root/config.json" <<EOF
{
  "format": "turboism.runtime.config",
  "schemaVersion": 1,
  "worktreeId": "$worktree_id",
  "safeMode": false,
  "logLevel": "INFO",
  "pluginDirs": ["plugins"],
  "hooks": {
    "disabledIds": [],
    "denylistedClasses": [],
    "startup": {
      "skipUpdateCheck": true,
      "skipSplash": true,
      "skipInformation": true
    }
  }
}
EOF

tmp="$(mktemp -d "$repo_root/build/.history-panel-probe.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/$probe_class_dir" "$tmp/META-INF/turboism/i18n"
find "$test_classes/$probe_class_dir" -maxdepth 1 -type f \
  \( -name "$seed_class.class" -o -name "$seed_class\$*.class" \
     -o -name "$probe_class.class" -o -name "$probe_class\$*.class" \) \
  -exec cp {} "$tmp/$probe_class_dir/" \;
cp "$seed_descriptor" "$tmp/META-INF/turboism/plugin.json"
: > "$tmp/META-INF/turboism/i18n/messages.properties"
(
  cd "$tmp"
  mapfile -t classes < <(find "$probe_class_dir" -type f -printf '%p\n' | LC_ALL=C sort)
  [ "${#classes[@]}" -gt 0 ] || { printf 'error: seed classes missing\n' >&2; exit 1; }
  jar --create --file "$bundle_root/plugins/history-seed-validation-probe.jar" \
    "${classes[@]}" META-INF/turboism/plugin.json META-INF/turboism/i18n/messages.properties
)
if jar tf "$bundle_root/plugins/history-seed-validation-probe.jar" | grep -Eq 'WindowsHistorySeedValidationProbeTest|\.java$'; then
  printf 'error: seed package contains test/source artifacts\n' >&2
  exit 1
fi
if ! jar tf "$bundle_root/plugins/history-seed-validation-probe.jar" \
  | grep -Fxq 'META-INF/turboism/i18n/messages.properties'; then
  printf 'error: seed probe package is missing its declared base i18n catalog\n' >&2
  exit 1
fi
if ! jar tf "$bundle_root/plugins/history-seed-validation-probe.jar" \
  | grep -Fxq "$probe_class_dir/$probe_class.class"; then
  printf 'error: seed probe package is missing the embedded native sampler class\n' >&2
  exit 1
fi
if ! jar tf "$bundle_root/plugins/history-seed-validation-probe.jar" \
  | grep -Fxq "$probe_class_dir/$probe_class\$Snapshot.class"; then
  printf 'error: seed probe package is missing embedded sampler dependencies\n' >&2
  exit 1
fi

tmp2="$(mktemp -d "$repo_root/build/.history-panel-readonly.XXXXXX")"
trap 'rm -rf "$tmp" "$tmp2"' EXIT
mkdir -p "$tmp2/$probe_class_dir" "$tmp2/META-INF/turboism/i18n"
find "$test_classes/$probe_class_dir" -maxdepth 1 -type f \
  \( -name "$probe_class.class" -o -name "$probe_class\$*.class" \) \
  -exec cp {} "$tmp2/$probe_class_dir/" \;
cp "$probe_descriptor" "$tmp2/META-INF/turboism/plugin.json"
: > "$tmp2/META-INF/turboism/i18n/messages.properties"
(
  cd "$tmp2"
  mapfile -t classes < <(find "$probe_class_dir" -type f -printf '%p\n' | LC_ALL=C sort)
  [ "${#classes[@]}" -gt 1 ] || { printf 'error: nested probe classes missing\n' >&2; exit 1; }
  jar --create --file "$bundle_root/plugins/history-validation-probe.jar" \
    "${classes[@]}" META-INF/turboism/plugin.json META-INF/turboism/i18n/messages.properties
)
if jar tf "$bundle_root/plugins/history-validation-probe.jar" | grep -Eq 'WindowsHistoryManagerValidationProbeTest|\.java$'; then
  printf 'error: probe package contains test/source artifacts\n' >&2
  exit 1
fi
if ! jar tf "$bundle_root/plugins/history-validation-probe.jar" \
  | grep -Fxq 'META-INF/turboism/i18n/messages.properties'; then
  printf 'error: history-manager probe package is missing its declared base i18n catalog\n' >&2
  exit 1
fi

tmp3="$(mktemp -d "$repo_root/build/.history-float.XXXXXX")"
trap 'rm -rf "$tmp" "$tmp2" "$tmp3"' EXIT
mkdir -p "$tmp3/$probe_class_dir" "$tmp3/META-INF/turboism/i18n"
find "$test_classes/$probe_class_dir" -maxdepth 1 -type f \
  \( -name "$float_class.class" -o -name "$float_class\$*.class" \) \
  -exec cp {} "$tmp3/$probe_class_dir/" \;
cp "$float_descriptor" "$tmp3/META-INF/turboism/plugin.json"
: > "$tmp3/META-INF/turboism/i18n/messages.properties"
(
  cd "$tmp3"
  mapfile -t classes < <(find "$probe_class_dir" -type f -printf '%p\n' | LC_ALL=C sort)
  [ "${#classes[@]}" -gt 0 ] || { printf 'error: float classes missing\n' >&2; exit 1; }
  jar --create --file "$bundle_root/plugins/history-float-probe.jar" \
    "${classes[@]}" META-INF/turboism/plugin.json META-INF/turboism/i18n/messages.properties
)
if jar tf "$bundle_root/plugins/history-float-probe.jar" | grep -Eq 'WindowsHistoryFloatProbeTest|\.java$'; then
  printf 'error: float package contains test/source artifacts\n' >&2
  exit 1
fi
if ! jar tf "$bundle_root/plugins/history-float-probe.jar" \
  | grep -Fxq 'META-INF/turboism/i18n/messages.properties'; then
  printf 'error: history-float probe package is missing its declared base i18n catalog\n' >&2
  exit 1
fi

tmp4="$(mktemp -d "$repo_root/build/.history-native-ui.XXXXXX")"
trap 'rm -rf "$tmp" "$tmp2" "$tmp3" "$tmp4"' EXIT
mkdir -p "$tmp4/$probe_class_dir" "$tmp4/META-INF/turboism/i18n"
# The operator-driven probe embeds the native sampler it reuses for its snapshots, so the
# read-only manager probe classes are packaged here too.
find "$test_classes/$probe_class_dir" -maxdepth 1 -type f \
  \( -name "$native_ui_class.class" -o -name "$native_ui_class\$*.class" \
     -o -name "$probe_class.class" -o -name "$probe_class\$*.class" \
     -o -name "$mesh_edit_class.class" -o -name "$mesh_edit_class\$*.class" \) \
  -exec cp {} "$tmp4/$probe_class_dir/" \;
cp "$native_ui_descriptor" "$tmp4/META-INF/turboism/plugin.json"
: > "$tmp4/META-INF/turboism/i18n/messages.properties"
(
  cd "$tmp4"
  mapfile -t classes < <(find "$probe_class_dir" -type f -printf '%p\n' | LC_ALL=C sort)
  [ "${#classes[@]}" -gt 1 ] || { printf 'error: native UI probe classes missing\n' >&2; exit 1; }
  jar --create --file "$bundle_root/plugins/history-native-ui-probe.jar" \
    "${classes[@]}" META-INF/turboism/plugin.json META-INF/turboism/i18n/messages.properties
)
if jar tf "$bundle_root/plugins/history-native-ui-probe.jar" | grep -Eq 'ProbeTest|\.java$'; then
  printf 'error: native UI probe package contains test/source artifacts\n' >&2
  exit 1
fi
if ! jar tf "$bundle_root/plugins/history-native-ui-probe.jar" \
  | grep -Fxq "$probe_class_dir/$native_ui_class.class"; then
  printf 'error: native UI probe package is missing its entrypoint\n' >&2
  exit 1
fi
if ! jar tf "$bundle_root/plugins/history-native-ui-probe.jar" \
  | grep -Fxq "$probe_class_dir/$probe_class.class"; then
  printf 'error: native UI probe package is missing the embedded native sampler\n' >&2
  exit 1
fi
if ! jar tf "$bundle_root/plugins/history-native-ui-probe.jar" \
  | grep -Fxq "$probe_class_dir/$mesh_edit_class\$SelectionAttempt.class"; then
  printf 'error: native UI probe package is missing the Parts-tree actor helper\n' >&2
  exit 1
fi

(
  cd "$bundle_root"
  sha256sum turboism-agent.jar plugins/history-panel.jar plugins/history-seed-validation-probe.jar \
    plugins/history-validation-probe.jar \
    plugins/history-float-probe.jar plugins/history-native-ui-probe.jar \
    launch-cubism-history-validation.ps1 run-history-validation.bat README.md \
    README-native-ui-validation.md config.json > SHA256SUMS.txt
)

printf '[package] Windows history-panel validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
