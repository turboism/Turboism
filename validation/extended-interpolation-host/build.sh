#!/usr/bin/env bash
# Builds the 5.3.04 Extended Interpolation Host Probe Agent JAR.
# The probe is validation-only tooling; it is never part of the production bundle.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
ref_dir="/opt/dev/projects/turboism/cubism-ref/5.3.04"
probe_src="$repo_root/validation/extended-interpolation-host/src"
pkg="dev/turboism/validation/extendedinterpolation/host"

if [ ! -d "$ref_dir" ]; then
  echo "error: 5.3.04 official reference directory missing: $ref_dir" >&2
  exit 1
fi

live2d_jar="$ref_dir/Live2D_Cubism.jar"
kotlin_jar="$ref_dir/kotlin-stdlib-1.7.21.jar"

if [ ! -f "$live2d_jar" ]; then
  echo "error: 5.3.04 Live2D_Cubism.jar not found at $live2d_jar" >&2
  exit 1
fi

# Gate 0: Verify 5.3.04 Live2D_Cubism.jar golden SHA-256
expected_host_sha="c9bb3e9960bf49908a531b7010421c393a8dd5f9376745eb6fdb273f41a1cf5d"
actual_host_sha="$(sha256sum "$live2d_jar" | cut -d' ' -f1)"
if [ "$actual_host_sha" != "$expected_host_sha" ]; then
  echo "error: 5.3.04 Live2D_Cubism.jar SHA-256 mismatch!" >&2
  echo "  expected: $expected_host_sha" >&2
  echo "  actual:   $actual_host_sha" >&2
  exit 1
fi

# Ensure output directory exists under build/
mkdir -p "$repo_root/build"
output_jar="$repo_root/build/extended-interpolation-host-probe.jar"

# Build in isolated temporary directory under /tmp to prevent cwd pollution from official classes
work_dir="$(mktemp -d /tmp/turboism-ei-host-build-XXXXXX)"
trap 'rm -rf "$work_dir"' EXIT

classes_dir="$work_dir/classes"
mkdir -p "$classes_dir"

echo "[ei-host-build] Compiling ExtendedInterpolationHostProbe against 5.3.04 reference JAR..."
javac --release 17 \
  -cp "$live2d_jar:$kotlin_jar" \
  -d "$classes_dir" \
  "$probe_src/$pkg/ExtendedInterpolationHostProbe.java" \
  "$probe_src/$pkg/GlPixelCapture.java" \
  "$probe_src/$pkg/WarpDragValidation.java"

echo "[ei-host-build] Packaging agent JAR with Premain-Class manifest..."
manifest_file="$work_dir/MANIFEST.MF"
cat << 'EOF' > "$manifest_file"
Manifest-Version: 1.0
Premain-Class: dev.turboism.validation.extendedinterpolation.host.ExtendedInterpolationHostProbe
Can-Redefine-Classes: true
Can-Retransform-Classes: true
EOF

jar cfm "$output_jar" "$manifest_file" -C "$classes_dir" .

echo "[ei-host-build] Verifying JAR content contract..."
# Gate 1: Must contain probe entry class
if ! jar tf "$output_jar" | grep -Fqx "$pkg/ExtendedInterpolationHostProbe.class"; then
  echo "error: output jar is missing ExtendedInterpolationHostProbe.class" >&2
  exit 1
fi

# Gate 2: Must NOT contain any official classes (com.live2d)
if jar tf "$output_jar" | grep -Fq "com/live2d/"; then
  echo "error: output jar must not leak host classes (found com/live2d)" >&2
  exit 1
fi

# Gate 3: Premain-Class attribute check (unfold manifest line-wraps per JAR specification)
premain_val="$(unzip -p "$output_jar" META-INF/MANIFEST.MF | sed -e ':a' -e 'N' -e '$!ba' -e 's/\r//g' -e 's/\n //g' | grep -F "Premain-Class:" | tr -d '\r\n' | awk '{print $2}')"
if [ "$premain_val" != "dev.turboism.validation.extendedinterpolation.host.ExtendedInterpolationHostProbe" ]; then
  echo "error: Manifest Premain-Class mismatch: $premain_val" >&2
  exit 1
fi

echo "[ei-host-build] Built host probe agent successfully:"
echo "  Artifact: $output_jar"
echo "  SHA-256:  $(sha256sum "$output_jar" | cut -d' ' -f1)"
