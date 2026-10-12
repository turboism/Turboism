#!/usr/bin/env bash
# Packages the test-only host dialog automation probe bundle.
# Usage: bash scripts/preview/package-windows-dialog-automation-validation.sh [bundle_root]
# Depends on: ./gradlew previewBundle :testing:integration-tests:testClasses
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
# shellcheck source=bundle-safety.sh
. "$repo_root/scripts/preview/bundle-safety.sh"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"

# Rebuild every shipped artifact before copying so a stale jar can never be
# packaged next to a newer agent (same reason as package-windows-mcp-validation.sh).
TURBOISM_WORKTREE_ID="$worktree_id" "$repo_root/gradlew" --console=plain \
  previewBundle :testing:integration-tests:testClasses

bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-dialog-automation-validation}"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
test_classes="$repo_root/build/worktree/$worktree_id/integration-tests/classes/java/test"
probe_class_rel="dev/turboism/tests/plugin/HostDialogAutomationValidationProbe.class"
probe_class_dir_rel="dev/turboism/tests/plugin"
probe_descriptor="$repo_root/scripts/preview/dialog-automation-validation-plugin.json"

if [ ! -f "$agent_jar" ]; then
  printf 'error: preview agent not found: %s\n' "$agent_jar" >&2
  printf 'run: ./gradlew previewBundle :testing:integration-tests:testClasses\n' >&2
  exit 1
fi
[ -f "$test_classes/$probe_class_rel" ] || {
  printf 'error: validation probe class not found: %s\n' "$test_classes/$probe_class_rel" >&2
  exit 1
}
[ -f "$probe_descriptor" ] || {
  printf 'error: validation descriptor not found: %s\n' "$probe_descriptor" >&2
  exit 1
}

preview_bundle_safe_remove_tree "$bundle_root" "$repo_root/build"
mkdir -p "$bundle_root/plugins"
cp "$agent_jar" "$bundle_root/turboism-agent.jar"

probe_tmp="$(mktemp -d "$repo_root/build/.dialog-automation-probe.XXXXXX")"
trap 'rm -rf "$probe_tmp"' EXIT
mkdir -p "$probe_tmp/$probe_class_dir_rel" "$probe_tmp/dev/turboism/validation" \
  "$probe_tmp/META-INF/turboism"
find "$test_classes/$probe_class_dir_rel" -maxdepth 1 -type f \
  \( -name 'HostDialogAutomationValidationProbe.class' \
     -o -name 'HostDialogAutomationValidationProbe$*.class' \
     -o -name 'WindowsParameterValidationProbe.class' \
     -o -name 'WindowsParameterValidationProbe$*.class' \) \
  -exec cp {} "$probe_tmp/$probe_class_dir_rel/" \;
# The probe delegates startup/warning answers to the shared audited responder;
# ship it inside the probe jar so the plugin classloader resolves it.
find "$test_classes/dev/turboism/validation" -maxdepth 1 -type f \
  -name 'NativeFreeStartup*.class' \
  -exec cp {} "$probe_tmp/dev/turboism/validation/" \;
cp "$probe_descriptor" "$probe_tmp/META-INF/turboism/plugin.json"
mkdir -p "$probe_tmp/META-INF/turboism/i18n"
: > "$probe_tmp/META-INF/turboism/i18n/messages.properties"
(
  cd "$probe_tmp"
  mapfile -t probe_classes < <(
    find "$probe_class_dir_rel" -maxdepth 1 -type f \
      \( -name 'HostDialogAutomationValidationProbe.class' \
         -o -name 'HostDialogAutomationValidationProbe$*.class' \
         -o -name 'WindowsParameterValidationProbe.class' \
         -o -name 'WindowsParameterValidationProbe$*.class' \) \
      -printf '%p\n' | LC_ALL=C sort
  )
  mapfile -t startup_classes < <(
    find "dev/turboism/validation" -maxdepth 1 -type f \
      -name 'NativeFreeStartup*.class' -printf '%p\n' | LC_ALL=C sort
  )
  [ "${#probe_classes[@]}" -ge 1 ] || {
    printf 'error: validation probe classes were not packaged\n' >&2
    exit 1
  }
  [ "${#startup_classes[@]}" -ge 1 ] || {
    printf 'error: audited startup responder class was not packaged\n' >&2
    exit 1
  }
  jar --create --file "$bundle_root/plugins/validation-probe.jar" \
    "${probe_classes[@]}" \
    "${startup_classes[@]}" \
    META-INF/turboism/plugin.json \
    META-INF/turboism/i18n/messages.properties
)
if jar tf "$bundle_root/plugins/validation-probe.jar" \
  | grep -Eq 'HostDialogAutomationValidationProbeTest|\.java$'; then
  printf 'error: validation probe package contains test/source artifacts\n' >&2
  exit 1
fi

(
  cd "$bundle_root"
  sha256sum \
    turboism-agent.jar \
    plugins/validation-probe.jar > SHA256SUMS.txt
)

printf '[package] Windows dialog automation validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
