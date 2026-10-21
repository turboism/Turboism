#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
id="$(bash "$root/scripts/dev/worktree-id.sh")"
sdk=("$root/build/worktree/$id/sdk/libs/"*.jar)
[[ ${#sdk[@]} == 1 && -f "${sdk[0]}" ]] || { echo 'Build :sdk:jar first' >&2; exit 2; }
out="$root/build/nesting-capture-probe"
mkdir -p "$out/classes"
javac --release 17 -cp "${sdk[0]}" -d "$out/classes" \
  "$root/validation/atlas-nesting-host-probe/src/dev/turboism/validation/nesting/NestingCapturePlugin.java"
cp -R "$root/validation/atlas-nesting-host-probe/src/META-INF" "$out/classes/"
jar cf "$out/nesting-capture-probe.jar" -C "$out/classes" .
java --class-path "$root/build/preview/$id/turboism-agent.jar" \
  "$root/validation/atlas-nesting-host-probe/ProbeMetadataCheck.java" "$out/nesting-capture-probe.jar"
sha256sum "$out/nesting-capture-probe.jar"
