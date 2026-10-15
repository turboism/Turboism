#!/usr/bin/env bash
# Builds the auto-atlas host probe plugin jar without proprietary dependencies.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"

base="validation/model-import-3d-auto-atlas"
sdk_path="${SDK_PATH:-}"
out_dir=""
runtime_path=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --sdk) [[ $# -ge 2 ]] || { echo "error: --sdk requires path" >&2; exit 2; }; sdk_path="$2"; shift 2 ;;
    --runtime) [[ $# -ge 2 ]] || exit 2; runtime_path="$2"; shift 2 ;;
    --out-dir) [[ $# -ge 2 ]] || { echo "error: --out-dir requires path" >&2; exit 2; }; out_dir="$2"; shift 2 ;;
    *) echo "error: unknown argument $1" >&2; exit 2 ;;
  esac
done

if [ -z "$sdk_path" ]; then
  # Find built SDK jar dynamically
  sdk_path="$(find build -name 'sdk-*.jar' 2>/dev/null | head -n 1 || true)"
fi

if [ -z "$sdk_path" ] || [ ! -f "$sdk_path" ]; then
  echo "error: sdk.jar not found; build it with ./gradlew :sdk:jar or provide via --sdk <path>" >&2
  exit 1
fi

if [ -z "$out_dir" ]; then
  timestamp="$(date +%Y%m%d_%H%M%S)"
  out_dir="build/validation/auto-atlas/run-$timestamp"
fi

classes="$out_dir/classes"
mkdir -p "$classes"

# Compile probe using SDK jar with strict compiler flags
javac -Xlint:all -Werror --release 17 \
  -cp "$sdk_path" \
  -d "$classes" \
  "$base/src/dev/turboism/validation/autoatlas/SyntheticAssets.java" \
  "$base/src/dev/turboism/validation/autoatlas/AutoAtlasHostProbePlugin.java"

# Copy the complete descriptor and declared resource catalogs.
cp -R "$base/src/META-INF" "$classes/"

jar_path="$out_dir/auto-atlas-host-probe.jar"
jar --create --file "$jar_path" -C "$classes" .

# Provide a convenience symlink or copy to the latest jar without destroying historical runs
mkdir -p "build/validation/auto-atlas"
cp "$jar_path" "build/validation/auto-atlas/auto-atlas-host-probe-latest.jar"

printf '[probe] Built %s\n' "$jar_path"
sha256sum "$jar_path"

if [ -n "$runtime_path" ]; then
  javac --release 17 -cp "$runtime_path" -d "$out_dir/preflight" "$base/test/ProbePreflight.java"
  java -cp "$runtime_path:$out_dir/preflight" ProbePreflight "$jar_path"
fi
