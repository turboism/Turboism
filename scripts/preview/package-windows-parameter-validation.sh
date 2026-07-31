#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

worktree_id="${TURBOISM_WORKTREE_ID:-main}"
bundle_root="${1:-$repo_root/build/manual-test/$worktree_id/windows-parameter-validation}"
agent_jar="$repo_root/build/preview/$worktree_id/turboism-agent.jar"
parameter_jar="$repo_root/build/worktree/$worktree_id/parameter/libs/parameter-0.1.0-SNAPSHOT-$worktree_id.jar"
test_classes="$repo_root/build/worktree/$worktree_id/tests/classes/java/test"
probe_class_rel="dev/turboism/tests/plugin/WindowsParameterValidationProbe.class"
probe_class_dir_rel="dev/turboism/tests/plugin"
probe_descriptor="$repo_root/scripts/preview/windows-parameter-validation-plugin.json"
peer_probe_class_rel="dev/turboism/tests/plugin/WindowsEditorObjectPeerValidationProbe.class"
peer_probe_descriptor="$repo_root/scripts/preview/windows-editor-object-peer-validation-plugin.json"
launcher_script="$repo_root/scripts/preview/launch-cubism-parameter-validation.ps1"

if [ ! -f "$agent_jar" ]; then
  printf 'error: preview agent not found: %s\n' "$agent_jar" >&2
  printf 'run: ./gradlew previewBundle :plugins:parameter:jar :tests:testClasses\n' >&2
  exit 1
fi
[ -f "$parameter_jar" ] || { printf 'error: parameter plugin jar not found: %s\n' "$parameter_jar" >&2; exit 1; }
[ -f "$test_classes/$probe_class_rel" ] || { printf 'error: validation probe class not found: %s\n' "$test_classes/$probe_class_rel" >&2; exit 1; }
[ -f "$test_classes/$peer_probe_class_rel" ] || { printf 'error: peer validation probe class not found: %s\n' "$test_classes/$peer_probe_class_rel" >&2; exit 1; }
[ -f "$probe_descriptor" ] || { printf 'error: validation descriptor not found: %s\n' "$probe_descriptor" >&2; exit 1; }
[ -f "$peer_probe_descriptor" ] || { printf 'error: peer validation descriptor not found: %s\n' "$peer_probe_descriptor" >&2; exit 1; }
[ -f "$launcher_script" ] || { printf 'error: validation launcher not found: %s\n' "$launcher_script" >&2; exit 1; }
if grep -Ein '\$home([^[:alnum:]_]|$)' "$launcher_script"; then
  printf 'error: validation launcher must not reference PowerShell read-only $HOME; use $turboismHome\n' >&2
  exit 1
fi

rm -rf "$bundle_root"
mkdir -p "$bundle_root/plugins" "$bundle_root/logs" "$bundle_root/state" "$bundle_root/plugin-data"
cp "$agent_jar" "$bundle_root/turboism-agent.jar"
cp "$parameter_jar" "$bundle_root/plugins/parameter.jar"
cp "$launcher_script" "$bundle_root/"
SOURCE_DATE_EPOCH="${SOURCE_DATE_EPOCH:-1785456000}" \
  python3 "$repo_root/scripts/release/package-plugin.py" \
  "$parameter_jar" "$bundle_root/parameter.tplugin"
cp "$repo_root/scripts/preview/run-parameter-validation.bat" "$bundle_root/"
cp "$repo_root/scripts/preview/README-parameter-validation.md" "$bundle_root/README.md"

probe_tmp="$(mktemp -d "$repo_root/build/.parameter-probe.XXXXXX")"
trap 'rm -rf "$probe_tmp" "${peer_tmp:-}"' EXIT
mkdir -p "$probe_tmp/$probe_class_dir_rel" "$probe_tmp/META-INF/turboism"
find "$test_classes/$probe_class_dir_rel" -maxdepth 1 -type f \
  \( -name 'WindowsParameterValidationProbe.class' \
     -o -name 'WindowsParameterValidationProbe$*.class' \) \
  -exec cp {} "$probe_tmp/$probe_class_dir_rel/" \;
cp "$probe_descriptor" "$probe_tmp/META-INF/turboism/plugin.json"
(
  cd "$probe_tmp"
  mapfile -t probe_classes < <(
    find "$probe_class_dir_rel" -maxdepth 1 -type f \
      \( -name 'WindowsParameterValidationProbe.class' \
         -o -name 'WindowsParameterValidationProbe$*.class' \) \
      -printf '%p\n' | LC_ALL=C sort
  )
  [ "${#probe_classes[@]}" -gt 1 ] || {
    printf 'error: validation probe nested classes were not packaged\n' >&2
    exit 1
  }
  jar --create --file "$bundle_root/plugins/parameter-validation-probe.jar" \
    "${probe_classes[@]}" \
    META-INF/turboism/plugin.json
)
if jar tf "$bundle_root/plugins/parameter-validation-probe.jar" \
  | grep -Eq 'WindowsParameterValidationProbeTest|\.java$'; then
  printf 'error: validation probe package contains test/source artifacts\n' >&2
  exit 1
fi

peer_tmp="$(mktemp -d "$repo_root/build/.editor-object-peer-probe.XXXXXX")"
mkdir -p "$peer_tmp/$probe_class_dir_rel" "$peer_tmp/META-INF/turboism"
cp "$test_classes/$peer_probe_class_rel" "$peer_tmp/$probe_class_dir_rel/"
cp "$peer_probe_descriptor" "$peer_tmp/META-INF/turboism/plugin.json"
(
  cd "$peer_tmp"
  jar --create --file "$bundle_root/plugins/editor-object-peer-validation-probe.jar" \
    "$peer_probe_class_rel" \
    META-INF/turboism/plugin.json
)

(
  cd "$bundle_root"
  sha256sum \
    turboism-agent.jar \
    plugins/parameter.jar \
    parameter.tplugin \
    plugins/parameter-validation-probe.jar \
    plugins/editor-object-peer-validation-probe.jar \
    launch-cubism-parameter-validation.ps1 \
    run-parameter-validation.bat \
    README.md > SHA256SUMS.txt
)

printf '[package] Windows parameter validation bundle: %s\n' "$bundle_root"
find "$bundle_root" -maxdepth 3 -type f -printf '  %P (%s bytes)\n' | LC_ALL=C sort
