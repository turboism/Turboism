#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="${TURBOISM_WORKTREE_ID:-$(scripts/dev/worktree-id.sh)}"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-history-panel-validation}"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
panel_jar="$repo_root/build/worktree/$worktree_id/history-panel/libs/history-panel-0.1.0-SNAPSHOT-$worktree_id.jar"
test_classes="$repo_root/build/worktree/$worktree_id/integration-tests/classes/java/test"
probe_class_dir="dev/turboism/tests/plugin"
probe_class="WindowsHistoryManagerValidationProbe"
probe_descriptor="$repo_root/scripts/preview/windows-history-manager-validation-plugin.json"
seed_class="WindowsHistorySeedValidationProbe"
float_class="WindowsHistoryFloatProbe"
float_descriptor="$repo_root/scripts/preview/windows-history-float-plugin.json"
seed_descriptor="$repo_root/scripts/preview/windows-history-seed-validation-plugin.json"
launcher="$repo_root/scripts/preview/launch-cubism-history-validation.ps1"

[ -f "$agent_jar" ] || { printf 'error: run ./gradlew previewBundle :plugins:history-panel:jar :testing:integration-tests:testClasses first\n' >&2; exit 1; }
[ -f "$panel_jar" ] || { printf 'error: history-panel plugin jar missing: %s\n' "$panel_jar" >&2; exit 1; }
[ -f "$test_classes/$probe_class_dir/$seed_class.class" ] || { printf 'error: seed class missing\n' >&2; exit 1; }

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/logs" "$bundle_root/state"
cp "$agent_jar" "$bundle_root/turboism-agent.jar"
cp "$panel_jar" "$bundle_root/plugins/history-panel.jar"
cp "$launcher" "$bundle_root/"
cp "$repo_root/scripts/preview/run-history-validation.bat" "$bundle_root/"
cp "$repo_root/scripts/preview/README-history-validation.md" "$bundle_root/README.md"
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
      "skipUpdateCheck": false,
      "skipSplash": false,
      "skipInformation": false
    }
  }
}
EOF

tmp="$(mktemp -d "$repo_root/build/.history-panel-probe.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
mkdir -p "$tmp/$probe_class_dir" "$tmp/META-INF/turboism"
find "$test_classes/$probe_class_dir" -maxdepth 1 -type f \
  \( -name "$seed_class.class" -o -name "$seed_class\$*.class" \) \
  -exec cp {} "$tmp/$probe_class_dir/" \;
cp "$seed_descriptor" "$tmp/META-INF/turboism/plugin.json"
(
  cd "$tmp"
  mapfile -t classes < <(find "$probe_class_dir" -type f -printf '%p\n' | LC_ALL=C sort)
  [ "${#classes[@]}" -gt 0 ] || { printf 'error: seed classes missing\n' >&2; exit 1; }
  jar --create --file "$bundle_root/plugins/history-seed-validation-probe.jar" \
    "${classes[@]}" META-INF/turboism/plugin.json
)
if jar tf "$bundle_root/plugins/history-seed-validation-probe.jar" | grep -Eq 'WindowsHistorySeedValidationProbeTest|\.java$'; then
  printf 'error: seed package contains test/source artifacts\n' >&2
  exit 1
fi

tmp2="$(mktemp -d "$repo_root/build/.history-panel-readonly.XXXXXX")"
trap 'rm -rf "$tmp" "$tmp2"' EXIT
mkdir -p "$tmp2/$probe_class_dir" "$tmp2/META-INF/turboism"
find "$test_classes/$probe_class_dir" -maxdepth 1 -type f \
  \( -name "$probe_class.class" -o -name "$probe_class\$*.class" \) \
  -exec cp {} "$tmp2/$probe_class_dir/" \;
cp "$probe_descriptor" "$tmp2/META-INF/turboism/plugin.json"
(
  cd "$tmp2"
  mapfile -t classes < <(find "$probe_class_dir" -type f -printf '%p\n' | LC_ALL=C sort)
  [ "${#classes[@]}" -gt 1 ] || { printf 'error: nested probe classes missing\n' >&2; exit 1; }
  jar --create --file "$bundle_root/plugins/history-validation-probe.jar" \
    "${classes[@]}" META-INF/turboism/plugin.json
)
if jar tf "$bundle_root/plugins/history-validation-probe.jar" | grep -Eq 'WindowsHistoryManagerValidationProbeTest|\.java$'; then
  printf 'error: probe package contains test/source artifacts\n' >&2
  exit 1
fi

tmp3="$(mktemp -d "$repo_root/build/.history-float.XXXXXX")"
trap 'rm -rf "$tmp" "$tmp2" "$tmp3"' EXIT
mkdir -p "$tmp3/$probe_class_dir" "$tmp3/META-INF/turboism"
find "$test_classes/$probe_class_dir" -maxdepth 1 -type f \
  \( -name "$float_class.class" -o -name "$float_class\$*.class" \) \
  -exec cp {} "$tmp3/$probe_class_dir/" \;
cp "$float_descriptor" "$tmp3/META-INF/turboism/plugin.json"
(
  cd "$tmp3"
  mapfile -t classes < <(find "$probe_class_dir" -type f -printf '%p\n' | LC_ALL=C sort)
  [ "${#classes[@]}" -gt 0 ] || { printf 'error: float classes missing\n' >&2; exit 1; }
  jar --create --file "$bundle_root/plugins/history-float-probe.jar" \
    "${classes[@]}" META-INF/turboism/plugin.json
)
if jar tf "$bundle_root/plugins/history-float-probe.jar" | grep -Eq 'WindowsHistoryFloatProbeTest|\.java$'; then
  printf 'error: float package contains test/source artifacts\n' >&2
  exit 1
fi

(
  cd "$bundle_root"
  sha256sum turboism-agent.jar plugins/history-panel.jar plugins/history-seed-validation-probe.jar \
    plugins/history-validation-probe.jar \
    plugins/history-float-probe.jar \
    launch-cubism-history-validation.ps1 run-history-validation.bat README.md config.json > SHA256SUMS.txt
)

printf '[package] Windows history-panel validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
