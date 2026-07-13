#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

PRODUCTION="runtime/src/main/java/dev/turboism/distribution/record"
FIXTURES="runtime/src/test/resources/fixtures/schema/distribution-protocol-v1"
TESTS="runtime/src/test/java/dev/turboism/distribution/record"

fail() { printf 'distribution protocol contract: %s\n' "$*" >&2; exit 1; }
require_nonempty_classes() {
  local label="$1" directory="$2"
  [[ -d "$directory" ]] || fail "$label classes directory missing: $directory"
  find "$directory" -type f -name '*.class' -print -quit | grep -q . \
    || fail "$label compiled classes are empty: $directory"
}
reject_compiled_distribution_refs() {
  local label="$1" directory="$2"
  while IFS= read -r -d '' class_file; do
    local class_name="${class_file#"$directory"/}"
    class_name="${class_name%.class}"
    class_name="${class_name//\//.}"
    if javap -classpath "$directory" -verbose "$class_name" 2>/dev/null \
      | grep -q 'dev/turboism/distribution'; then
      fail "$label compiled class references distribution internals: $class_file"
    fi
  done < <(find "$directory" -type f -name '*.class' -print0)
}

[[ -d "$PRODUCTION" ]] || fail "missing production protocol package"
[[ -d "$FIXTURES/valid" && -d "$FIXTURES/invalid" ]] || fail "missing persistent fixture matrix"
[[ -f "$FIXTURES/diagnostic-mapping.tsv" ]] || fail "missing stable diagnostic mapping TSV"
[[ -f "$TESTS/ProtocolFixtureMatrixTest.java" ]] || fail "missing fixture matrix test"

valid_count="$(find "$FIXTURES/valid" -maxdepth 1 -type f -name '*.json' | wc -l)"
invalid_count="$(find "$FIXTURES/invalid" -maxdepth 1 -type f -name '*.json' | wc -l)"
(( valid_count >= 3 )) || fail "need all three directorySync valid fixtures"
(( invalid_count >= 3 )) || fail "need at least three invalid fixtures"

for source in "$PRODUCTION"/*.java; do
  lines="$(wc -l < "$source")"
  (( lines <= 200 )) || fail "$source exceeds 200 production lines"
  ! grep -Eq '^public (class|record|interface|enum) ' "$source" \
    || fail "$source must remain package-private"
  method_report="$(mktemp)"
  awk '
    function finish() { if (method && lines > 30) { print method ":" lines; failed=1 } }
    /^[[:space:]]*(static |private |final |protected )*[A-Za-z0-9_<>, ?\[\]]+[[:space:]]+[A-Za-z0-9_]+\([^;]*\)[[:space:]]*\{/ {
      finish(); method=FNR; lines=1; depth=1; next
    }
    method {
      lines++
      opens=gsub(/\{/, "{"); closes=gsub(/\}/, "}"); depth += opens - closes
      if (depth == 0) { finish(); method=0; lines=0 }
    }
    END { finish(); exit failed }
  ' "$source" >"$method_report" \
    || { cat "$method_report" >&2; rm -f "$method_report"; fail "$source has a method over 30 lines"; }
  rm -f "$method_report"
done
! grep -R -E '^import java\.nio\.file\.(Path|Files);' "$PRODUCTION" \
  || fail "protocol production code must not use Path or Files"

for restricted in sdk/src/main/java plugins/*/src/main/java; do
  [[ -d "$restricted" ]] || continue
  ! grep -R -E '^import dev\.turboism\.distribution(\.|;)' "$restricted" \
    || fail "SDK/plugins must not import distribution internals: $restricted"
done

sdk_classes="${TURBOISM_SDK_CLASSES_DIR:-}"
[[ -n "$sdk_classes" ]] || fail "TURBOISM_SDK_CLASSES_DIR is required"
require_nonempty_classes "sdk" "$sdk_classes"
reject_compiled_distribution_refs "sdk" "$sdk_classes"

plugin_dirs="${TURBOISM_PLUGIN_CLASSES_DIRS:-}"
[[ -n "$plugin_dirs" ]] || fail "TURBOISM_PLUGIN_CLASSES_DIRS is required"
IFS=':' read -r -a directories <<< "$plugin_dirs"
(( ${#directories[@]} > 0 )) || fail "no plugin class directories supplied"
for directory in "${directories[@]}"; do
  require_nonempty_classes "plugin" "$directory"
  reject_compiled_distribution_refs "plugin" "$directory"
done

printf 'distribution protocol contract: PASS\n'
