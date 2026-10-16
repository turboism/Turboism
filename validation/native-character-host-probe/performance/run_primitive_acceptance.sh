#!/usr/bin/env bash
# Usage: bash run_primitive_acceptance.sh PLUGIN_JAR SDK_OR_RUNTIME_JAR OUTPUT_DIR
# Exit 1 means the geometry screen found a defect; outputs are retained for review.
set -euo pipefail
plugin_jar="$(realpath "$1")"
sdk_jar="$(realpath "$2")"
mkdir -p "$3"
output_dir="$(realpath "$3")"
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
python3 "$script_dir/create_primitive_fixtures.py" "$output_dir/fixtures" > "$output_dir/fixtures.log"
mkdir -p "$output_dir/classes"
javac --release 17 -cp "$plugin_jar:$sdk_jar" -d "$output_dir/classes" \
  "$script_dir/NativeOcclusionDiagnostic.java" "$script_dir/PrimitiveAcceptance.java"
sha256sum "$plugin_jar" "$sdk_jar" > "$output_dir/toolchain-sha256.txt"
java -Xmx2g -cp "$output_dir/classes:$plugin_jar:$sdk_jar" \
  dev.turboism.validation.modelimport3d.host.PrimitiveAcceptance \
  "$output_dir/fixtures" "$output_dir/results" > "$output_dir/run.log" 2>&1
