#!/usr/bin/env bash
# Package the recent-preview real-host validation drop from this worktree's own build.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-recent-preview-validation}"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
plugin_jar_dir="$repo_root/build/worktree/$worktree_id/recent-preview/libs"
test_classes="$repo_root/build/worktree/$worktree_id/integration-tests/classes/java/test"
probe_class_dir_rel="dev/turboism/tests/plugin"
probe_descriptor="$repo_root/scripts/preview/windows-recent-preview-validation-plugin.json"

printf '[package] building fresh worktree artifacts (bootstrap, recent-preview plugin, tests, preview bundle)\n'
./gradlew :bootstrap:jar :plugins:recent-preview:jar :testing:integration-tests:testClasses previewBundle --console=plain

[ -f "$agent_jar" ] || { printf 'error: preview agent not found: %s\n' "$agent_jar" >&2; exit 1; }
mapfile -t plugin_jars < <(
  find "$plugin_jar_dir" -maxdepth 1 -type f -name "recent-preview-*-$worktree_id.jar" \
    -printf '%p\n' | LC_ALL=C sort
)
[ "${#plugin_jars[@]}" -eq 1 ] || {
  printf 'error: expected exactly one recent-preview plugin jar under %s, found %d\n' \
    "$plugin_jar_dir" "${#plugin_jars[@]}" >&2
  printf '  %s\n' "${plugin_jars[@]:-<none>}" >&2
  exit 1
}
plugin_jar="${plugin_jars[0]}"
[ -f "$test_classes/$probe_class_dir_rel/WindowsRecentPreviewValidationProbe.class" ] \
  || { printf 'error: validation probe class not found under %s\n' "$test_classes" >&2; exit 1; }
[ -f "$probe_descriptor" ] || { printf 'error: validation descriptor not found: %s\n' "$probe_descriptor" >&2; exit 1; }

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins"
cp "$agent_jar" "$bundle_root/turboism-agent.jar"
cp "$plugin_jar" "$bundle_root/plugins/recent-preview.jar"

probe_tmp="$(mktemp -d "$repo_root/build/.recent-preview-probe.XXXXXX")"
trap 'rm -rf "$probe_tmp"' EXIT
mkdir -p "$probe_tmp/$probe_class_dir_rel" "$probe_tmp/META-INF/turboism"
find "$test_classes/$probe_class_dir_rel" -maxdepth 1 -type f \
  \( -name 'WindowsRecentPreviewValidationProbe.class' \
     -o -name 'WindowsRecentPreviewValidationProbe$*.class' \) \
  -exec cp {} "$probe_tmp/$probe_class_dir_rel/" \;
cp "$probe_descriptor" "$probe_tmp/META-INF/turboism/plugin.json"
(
  cd "$probe_tmp"
  mapfile -t probe_classes < <(
    find "$probe_class_dir_rel" -maxdepth 1 -type f \
      \( -name 'WindowsRecentPreviewValidationProbe.class' \
         -o -name 'WindowsRecentPreviewValidationProbe$*.class' \) \
      -printf '%p\n' | LC_ALL=C sort
  )
  [ "${#probe_classes[@]}" -gt 1 ] || {
    printf 'error: validation probe nested classes were not packaged\n' >&2
    exit 1
  }
  jar --create --file "$bundle_root/plugins/recent-preview-validation-probe.jar" \
    "${probe_classes[@]}" \
    META-INF/turboism/plugin.json
)
if jar tf "$bundle_root/plugins/recent-preview-validation-probe.jar" \
  | grep -Eq 'WindowsRecentPreviewValidationProbeTest|\.java$'; then
  printf 'error: validation probe package contains test/source artifacts\n' >&2
  exit 1
fi

(
  cd "$bundle_root"
  sha256sum \
    turboism-agent.jar \
    plugins/recent-preview.jar \
    plugins/recent-preview-validation-probe.jar > SHA256SUMS.txt
)

printf '[package] Windows recent-preview validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
