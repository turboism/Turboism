#!/usr/bin/env bash
# Offline build only. No host, hooks, downloads, or process management.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
id="$(bash "$root/scripts/dev/worktree-id.sh")"
agent="$root/build/preview/$id/turboism-agent.jar"
out="$root/build/atlas-queue-probe"
[[ -f "$agent" ]] || { echo 'Build previewBundle first' >&2; exit 2; }
mkdir -p "$out"
tmp="$(mktemp -d "$out/package.XXXXXX")"
trap 'rm -rf "$tmp"' EXIT
src="$root/validation/texture-atlas-current-page"
mkdir "$tmp/classes"
javac --release 17 -cp "$agent" -d "$tmp/classes" \
  "$src/HostUiProbe.java" "$src/HostTimingProbe.java" "$src/AtlasQueueProbe.java" "$src/HostAdmissionDiagnostic.java"
printf 'Premain-Class: dev.turboism.validation.texture.AtlasQueueProbe\nCan-Retransform-Classes: true\n\n' > "$tmp/MANIFEST.MF"
jar cfm "$tmp/atlas-queue-probe.jar" "$tmp/MANIFEST.MF" -C "$tmp/classes" .
# Include a fixed source inventory in the artifact prepared by the queue.
sha256sum "$src/HostUiProbe.java" "$src/HostTimingProbe.java" "$src/AtlasQueueProbe.java" "$src/HostAdmissionDiagnostic.java" "$agent" > "$tmp/dependencies.sha256"
jar uf "$tmp/atlas-queue-probe.jar" -C "$tmp" dependencies.sha256
mv "$tmp/atlas-queue-probe.jar" "$out/atlas-queue-probe.jar"
echo "$out/atlas-queue-probe.jar"
