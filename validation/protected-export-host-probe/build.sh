#!/usr/bin/env bash
# Builds the validation-only exact-host probe for the protected-export wiring.
#
# The probe is a premain agent, not a plugin: it must observe the native dialog that the production
# transformer instruments, and it must do so without linking against any host or SDK type. The
# leakage policy below is therefore part of the build, not a review step.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$repo_root"
base="validation/protected-export-host-probe"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
classes="$out/classes"
mkdir -p "$classes"
sources=(
  "$base/src/dev/turboism/validation/protectedexport/ProtectedExportHostProbeAgent.java"
  "$base/src/dev/turboism/validation/protectedexport/ProtectedExportProbeSelfCheck.java"
)
for source in "${sources[@]}"; do
  [ -f "$source" ] || { echo "error: missing source $source" >&2; exit 1; }
done
javac -Xlint:all -Werror --release 17 -d "$classes" "${sources[@]}"
java -cp "$classes" dev.turboism.validation.protectedexport.ProtectedExportProbeSelfCheck
mkdir -p build
jar_path="build/protected-export-host-probe-agent.jar"
manifest="$out/MANIFEST.MF"
printf 'Manifest-Version: 1.0\nPremain-Class: dev.turboism.validation.protectedexport.ProtectedExportHostProbeAgent\nCan-Redefine-Classes: false\nCan-Retransform-Classes: false\n\n' > "$manifest"
jar --create --file "$jar_path" --date=1980-01-01T00:00:02Z --manifest "$manifest" -C "$classes" dev
if jar tf "$jar_path" | grep -Eiq '(^|/)(test|tests|fixture|fixtures)/|com/live2d|dev/turboism/sdk|dev/turboism/bootstrap|dev/turboism/plugin'; then
  echo 'error: source/test/host/production leakage policy failed' >&2
  exit 1
fi
printf '[probe] %s\n' "$jar_path"
sha256sum "$jar_path"
