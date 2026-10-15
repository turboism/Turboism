#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
# Arguments: SDK jar, production plugin jar, runtime fat jar, output directory.
sdk="$1"; plugin="$2"; runtime="$3"; out="$4"
mkdir -p "$out/classes"
javac --release 17 -cp "$sdk:$plugin" -d "$out/classes" validation/model-viewer-host-probe/src/dev/turboism/validation/modelviewer/*.java
cp -R validation/model-viewer-host-probe/src/META-INF "$out/classes/"
jar --create --file "$out/viewer-host-probe.jar" -C "$out/classes" .
javac --release 17 -cp "$runtime" -d "$out/preflight" validation/model-import-3d-auto-atlas/test/ProbePreflight.java
java -cp "$runtime:$out/preflight" ProbePreflight "$out/viewer-host-probe.jar"
sha256sum "$plugin" "$out/viewer-host-probe.jar"
