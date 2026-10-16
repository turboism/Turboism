#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../.."
sdk="$1"
runtime="$2"
out="$3"
mkdir -p "$out/classes"
javac --release 17 -cp "$sdk" -d "$out/classes" validation/native-character-host-probe/src/dev/turboism/validation/nativecharacter/*.java
cp -R validation/native-character-host-probe/src/META-INF "$out/classes/"
jar --create --file "$out/native-character-host-probe.jar" -C "$out/classes" .
javac --release 17 -cp "$runtime:$sdk" -d "$out/preflight" validation/model-import-3d-auto-atlas/test/ProbePreflight.java
java -cp "$runtime:$sdk:$out/preflight" ProbePreflight "$out/native-character-host-probe.jar"
sha256sum "$out/native-character-host-probe.jar"
