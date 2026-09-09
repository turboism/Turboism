#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
id="$(bash scripts/dev/worktree-id.sh)"
shopt -s nullglob
sdk=("$root/build/worktree/$id/sdk/libs/"sdk-*.jar)
[[ ${#sdk[@]} -eq 1 ]] || { echo 'Build :sdk:jar first' >&2; exit 1; }
src="$root/validation/texture-relations-host-probe/src"
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 17 -cp "${sdk[0]}" -d "$out" "$src/dev/turboism/validation/textures/TextureRelationsHostProbe.java"
cp -R "$src/META-INF" "$out/"
jar cf "$root/build/texture-relations-host-probe.jar" -C "$out" .
jar tf "$root/build/texture-relations-host-probe.jar" | grep -Fx 'META-INF/turboism/i18n/messages.properties' >/dev/null
sha256sum "$root/build/texture-relations-host-probe.jar"
