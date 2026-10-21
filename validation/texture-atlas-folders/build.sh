#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
id="$(bash "$root/scripts/dev/worktree-id.sh")"
shopt -s nullglob
sdks=("$root/build/worktree/$id/sdk/libs/"*.jar)
[[ ${#sdks[@]} == 1 ]] || exit 2
sdk="${sdks[0]}"
[[ -f "$sdk" ]] || { echo 'Build :sdk:jar first' >&2; exit 2; }
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
javac --release 17 -cp "$sdk" -d "$tmp" "$root/validation/texture-atlas-folders/src/dev/turboism/validation/atlasfolders/FolderAtlasHostProbe.java"
cp -r "$root/validation/texture-atlas-folders/src/META-INF" "$tmp/"
jar cf "$root/build/folder-atlas-host-probe.jar" -C "$tmp" .
sha256sum "$root/build/folder-atlas-host-probe.jar"
