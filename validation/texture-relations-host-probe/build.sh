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
if [[ "${1:-}" == --export-observation ]]; then
  python3 - "$out/META-INF/turboism/plugin.json" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
meta = json.loads(path.read_text())
meta['name'] = 'PSD Native Export Observation'
meta['permissions'] += [
    {'id': permission, 'scope': 'application', 'reason': 'Export and parse only the isolated task fixture into OS temporary storage.'}
    for permission in ('turboism.file.read', 'turboism.file.write')
]
path.write_text(json.dumps(meta, indent=2) + '\n')
PY
  jar cf "$root/build/psd-export-observation-host-probe.jar" -C "$out" .
  sha256sum "$root/build/psd-export-observation-host-probe.jar"
  exit 0
fi
[[ $# -eq 0 ]] || { echo 'Usage: build.sh [--export-observation]' >&2; exit 2; }
jar cf "$root/build/texture-relations-host-probe.jar" -C "$out" .
jar tf "$root/build/texture-relations-host-probe.jar" | grep -Fx 'META-INF/turboism/i18n/messages.properties' >/dev/null
sha256sum "$root/build/texture-relations-host-probe.jar"
