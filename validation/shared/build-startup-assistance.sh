#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
base=validation/shared
javac --release 17 -d "$out/classes" \
  "$base/src/dev/turboism/validation/NativeFreeStartup.java" \
  "$base/src/dev/turboism/validation/NativeStartupAssistanceAgent.java"
javac --release 17 -cp "$out/classes" -d "$out/test" \
  "$base/test/dev/turboism/validation/NativeStartupAssistanceAgentTest.java"
java -cp "$out/classes:$out/test" dev.turboism.validation.NativeStartupAssistanceAgentTest
printf 'Manifest-Version: 1.0\nPremain-Class: dev.turboism.validation.NativeStartupAssistanceAgent\n\n' > "$out/MANIFEST.MF"
mkdir -p build
jar --create --file build/native-startup-assistance-agent.jar \
  --date=1980-01-01T00:00:02Z --manifest "$out/MANIFEST.MF" -C "$out/classes" .
sha256sum build/native-startup-assistance-agent.jar
