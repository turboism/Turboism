#!/usr/bin/env bash
# Packages the validation-only Turboism with fx exact-host bundle.
# Usage: bash scripts/preview/package-windows-fx-host-validation.sh [bundle_root]
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-fx-host-validation}"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
test_classes="$repo_root/build/worktree/$worktree_id/integration-tests/classes/java/test"
probe_class_dir_rel='dev/turboism/tests/plugin'
probe_descriptor="$repo_root/scripts/preview/fx-validation-plugin.json"
bridge_source="$repo_root/scripts/preview/fx-validation-bridge/acp.java"
broker_source="$repo_root/scripts/preview/fx-validation-bridge/fx_validation_broker.py"

for required in "$agent_jar" "$test_classes/$probe_class_dir_rel/FxHostValidationProbe.class" \
  "$probe_descriptor" "$bridge_source" "$broker_source"; do
  [ -f "$required" ] || {
    printf 'error: required fx validation artifact is missing: %s\n' "$required" >&2
    printf 'run: ./gradlew previewBundle :testing:integration-tests:testClasses :plugins:mcp:jar :plugins:turboism-with-fx:jar\n' >&2
    exit 1
  }
done

mcp_jar="$(find "$repo_root/build/worktree/$worktree_id/mcp/libs" -maxdepth 1 -type f -name '*.jar' | LC_ALL=C sort | tail -n 1)"
fx_plugin_jar="$(find "$repo_root/build/worktree/$worktree_id/turboism-with-fx/libs" -maxdepth 1 -type f -name '*.jar' | LC_ALL=C sort | tail -n 1)"
[ -f "$mcp_jar" ] && [ -f "$fx_plugin_jar" ] || {
  printf 'error: production plugin jars are missing\n' >&2
  exit 1
}

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/bridge" "$bundle_root/broker"
cp "$agent_jar" "$bundle_root/turboism-agent.jar"
cp "$mcp_jar" "$bundle_root/plugins/mcp.jar"
cp "$fx_plugin_jar" "$bundle_root/plugins/turboism-with-fx.jar"
cp "$broker_source" "$bundle_root/broker/fx_validation_broker.py"

probe_tmp="$(mktemp -d "$repo_root/build/.fx-host-validation-probe.XXXXXX")"
bridge_tmp="$(mktemp -d "$repo_root/build/.fx-host-validation-bridge.XXXXXX")"
trap 'rm -rf "$probe_tmp" "$bridge_tmp"' EXIT
mkdir -p "$probe_tmp/$probe_class_dir_rel" "$probe_tmp/META-INF/turboism/i18n"
find "$test_classes/$probe_class_dir_rel" -maxdepth 1 -type f \
  \( -name 'FxHostValidationProbe.class' -o -name 'FxHostValidationProbe$*.class' \) \
  -exec cp {} "$probe_tmp/$probe_class_dir_rel/" \;
cp "$probe_descriptor" "$probe_tmp/META-INF/turboism/plugin.json"
: > "$probe_tmp/META-INF/turboism/i18n/messages.properties"
(
  cd "$probe_tmp"
  mapfile -t classes < <(find "$probe_class_dir_rel" -maxdepth 1 -type f -name 'FxHostValidationProbe*.class' -printf '%p\n' | LC_ALL=C sort)
  [ "${#classes[@]}" -gt 0 ] || exit 1
  jar --create --file "$bundle_root/plugins/fx-host-validation-probe.jar" \
    "${classes[@]}" META-INF/turboism/plugin.json META-INF/turboism/i18n/messages.properties
)

javac --release 17 -d "$bridge_tmp" "$bridge_source"
jar --create --file "$bundle_root/bridge/fx-validation-bridge.jar" -C "$bridge_tmp" .

for archive in "$bundle_root/plugins/fx-host-validation-probe.jar" \
  "$bundle_root/bridge/fx-validation-bridge.jar"; do
  if jar tf "$archive" | grep -Eq '\.java$|FxHostValidationProbeTest'; then
    printf 'error: validation archive contains source/test artifacts: %s\n' "$archive" >&2
    exit 1
  fi
done
if find "$bundle_root" -type f \( -name fx -o -name 'fx-*tar*' -o -name LICENSE -o -name THIRD_PARTY_NOTICES.md \) | grep -q .; then
  printf 'error: validation bundle unexpectedly contains redistributed fx artifacts\n' >&2
  exit 1
fi

(
  cd "$bundle_root"
  find . -type f ! -name SHA256SUMS.txt -print0 | LC_ALL=C sort -z \
    | xargs -0 sha256sum > SHA256SUMS.txt
)

printf '[package] Windows fx host validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 4 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
