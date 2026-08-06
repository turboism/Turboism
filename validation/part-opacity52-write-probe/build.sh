#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
base="validation/part-opacity52-write-probe"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
classes="$out/classes"
mkdir -p "$classes"
sources=(
  "$base/src/dev/turboism/validation/partopacity/PartOpacity52WriteProbeAgent.java"
  "$base/src/dev/turboism/validation/partopacity/PartOpacity52WriteProbeSelfCheck.java"
)
for source in "${sources[@]}"; do
  [ -f "$source" ] || { echo "error: missing source $source" >&2; exit 1; }
done
javac --release 17 -d "$classes" "${sources[@]}"
java -cp "$classes" dev.turboism.validation.partopacity.PartOpacity52WriteProbeSelfCheck
mkdir -p build
jar_path="build/part-opacity52-write-probe-agent.jar"
manifest="$out/MANIFEST.MF"
printf 'Manifest-Version: 1.0\nPremain-Class: dev.turboism.validation.partopacity.PartOpacity52WriteProbeAgent\nCan-Redefine-Classes: false\nCan-Retransform-Classes: false\n\n' > "$manifest"
jar --create --file "$jar_path" --date=1980-01-01T00:00:02Z --manifest "$manifest" -C "$classes" dev
if jar tf "$jar_path" | grep -Eiq '(^|/)(test|tests|fixture|fixtures)/|com/live2d|dev/turboism/sdk|dev/turboism/bootstrap'; then
  echo 'error: source/test/host/production leakage policy failed' >&2
  exit 1
fi
printf '[probe] %s\n' "$jar_path"
sha256sum "$jar_path"
