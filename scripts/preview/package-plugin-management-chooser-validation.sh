#!/usr/bin/env bash
# Packages the task-local Plugin Management chooser/restart exact-host validation bundle.
# Usage: bash scripts/preview/package-plugin-management-chooser-validation.sh [bundle_root]
# Depends on: ./gradlew previewBundle :testing:integration-tests:testClasses
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="$(TURBOISM_WORKTREE_ID="${TURBOISM_WORKTREE_ID:-}" "$repo_root/scripts/dev/worktree-id.sh")"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-plugin-management-chooser-validation}"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
test_classes="$repo_root/build/worktree/$worktree_id/integration-tests/classes/java/test"
class_dir_rel="dev/turboism/tests/plugin"

if [ ! -f "$agent_jar" ]; then
  printf 'error: preview agent not found: %s\n' "$agent_jar" >&2
  printf 'run: ./gradlew previewBundle :testing:integration-tests:testClasses\n' >&2
  exit 1
fi

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/fixtures"
cp "$agent_jar" "$bundle_root/turboism-agent.jar"

package_probe() {
  local class_name=$1 descriptor=$2 output_name=$3
  local class_file="$test_classes/$class_dir_rel/$class_name.class"
  [ -f "$class_file" ] || { printf 'error: validation probe class not found: %s\n' "$class_file" >&2; exit 1; }
  [ -f "$descriptor" ] || { printf 'error: validation descriptor not found: %s\n' "$descriptor" >&2; exit 1; }

  local probe_tmp
  probe_tmp="$(mktemp -d "$repo_root/build/.plugin-management-probe.XXXXXX")"
  mkdir -p "$probe_tmp/$class_dir_rel" "$probe_tmp/META-INF/turboism/i18n"
  find "$test_classes/$class_dir_rel" -maxdepth 1 -type f \
    \( -name "$class_name.class" -o -name "$class_name\$*.class" \) \
    -exec cp {} "$probe_tmp/$class_dir_rel/" \;
  cp "$descriptor" "$probe_tmp/META-INF/turboism/plugin.json"
  : > "$probe_tmp/META-INF/turboism/i18n/messages.properties"
  python3 - "$probe_tmp" "$bundle_root/plugins/$output_name" <<'PY'
import stat
import sys
import zipfile
from pathlib import Path

root = Path(sys.argv[1])
output = Path(sys.argv[2])
paths = sorted(root.rglob("*"), key=lambda path: path.relative_to(root).as_posix())
files = [path for path in paths if path.is_file()]
if not files:
    raise SystemExit("validation probe package has no files")
with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
    for path in paths:
        relative = path.relative_to(root).as_posix()
        if path.is_dir():
            relative += "/"
        info = zipfile.ZipInfo(relative, (1980, 1, 1, 0, 0, 0))
        info.create_system = 3
        if path.is_dir():
            info.external_attr = (stat.S_IFDIR | 0o755) << 16 | 0x10
            archive.writestr(info, b"")
        else:
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = (stat.S_IFREG | 0o644) << 16
            archive.writestr(info, path.read_bytes())
PY
  rm -rf "$probe_tmp"
  if jar tf "$bundle_root/plugins/$output_name" | grep -Eq 'Test|\.java$'; then
    printf 'error: validation probe package contains test/source artifacts: %s\n' "$output_name" >&2
    exit 1
  fi
}

package_probe \
  PluginManagementChooserValidationProbe \
  "$repo_root/scripts/preview/plugin-management-chooser-validation-plugin.json" \
  plugin-management-chooser-validation-probe.jar
package_probe \
  PluginManagementRestartValidationProbe \
  "$repo_root/scripts/preview/plugin-management-restart-validation-plugin.json" \
  plugin-management-restart-validation-probe.jar
cp "$bundle_root/plugins/plugin-management-chooser-validation-probe.jar" \
  "$bundle_root/fixtures/install-target.jar"

(
  cd "$bundle_root"
  sha256sum turboism-agent.jar \
    plugins/plugin-management-chooser-validation-probe.jar \
    plugins/plugin-management-restart-validation-probe.jar \
    fixtures/install-target.jar > SHA256SUMS.txt
)

printf '[package] Plugin Management chooser validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
