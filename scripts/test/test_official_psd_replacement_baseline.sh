#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
id="$(bash "$root/scripts/dev/worktree-id.sh")"
shopt -s nullglob
sdk=("$root/build/worktree/$id/sdk/libs/"sdk-*.jar)
[[ ${#sdk[@]} -eq 1 ]] || {
  echo 'Replacement baseline test requires exactly one SDK JAR' >&2
  exit 2
}

shape_jar="${TURBOISM_EXTERNAL_PSD_SHAPE_JAR:-/opt/dev/projects/turboism-legacy/cubism-ref/Cubism-5.3.02/jars/Live2D_Cubism.jar}"
[[ -f "$shape_jar" ]] || {
  echo "Replacement baseline test requires the reviewed JAR: $shape_jar" >&2
  exit 2
}

out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT

javac --release 17 -Xlint:all -cp "${sdk[0]}" -d "$out" \
  "$root/validation/external-psd-edit-host-probe/src/dev/turboism/validation/externalpsd/OfficialPsdReplacementBaseline.java" \
  "$root/validation/external-psd-edit-host-probe/test/dev/turboism/validation/externalpsd/OfficialPsdReplacementBaselineTest.java"

shape_dir="$(dirname "$shape_jar")"
TURBOISM_EXTERNAL_PSD_SHAPE_JAR="$shape_jar" java -Djava.awt.headless=true \
  -cp "$out:${sdk[0]}:$shape_jar:$shape_dir/*" \
  dev.turboism.validation.externalpsd.OfficialPsdReplacementBaselineTest
