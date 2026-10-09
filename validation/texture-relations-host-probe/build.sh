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
javac --release 17 -cp "${sdk[0]}" -d "$out" "$src/dev/turboism/validation/textures/TextureRelationsHostProbe.java" "validation/shared/src/dev/turboism/validation/NativeFreeStartup.java" "validation/shared/src/dev/turboism/validation/SessionGate.java"
cp -R "$src/META-INF" "$out/"
if [[ "${1:-}" == --export-observation || "${1:-}" == --export-observation-session ]]; then
  session_mode=0
  [[ "${1:-}" == --export-observation-session ]] && session_mode=1
  python3 - "$out/META-INF/turboism/plugin.json" "$session_mode" <<'PY'
import json, pathlib, sys
path = pathlib.Path(sys.argv[1])
meta = json.loads(path.read_text())
meta['name'] = 'PSD Native Export Observation'
meta['permissions'] += [
    {'id': permission, 'scope': 'application', 'reason': 'Export and parse only the isolated task fixture into OS temporary storage.'}
    for permission in ('turboism.file.read', 'turboism.file.write')
]
if sys.argv[2] == '1':
    # Multi-probe sessions carry both texture steps; a duplicate plugin id would
    # deduplicate one jar out of the load set, so the session variant owns a
    # distinct identity (and therefore its own state dir and session go-flag).
    meta['id'] = 'dev.turboism.validation.textures.exportobs'
    meta['name'] = 'PSD Native Export Observation (session)'
path.write_text(json.dumps(meta, indent=2) + '\n')
PY
  if [[ "$session_mode" = 1 ]]; then
    jar cf "$root/build/psd-export-observation-session-probe.jar" -C "$out" .
    sha256sum "$root/build/psd-export-observation-session-probe.jar"
  else
    jar cf "$root/build/psd-export-observation-host-probe.jar" -C "$out" .
    sha256sum "$root/build/psd-export-observation-host-probe.jar"
  fi
  exit 0
fi
[[ $# -eq 0 ]] || { echo 'Usage: build.sh [--export-observation|--export-observation-session]' >&2; exit 2; }
jar cf "$root/build/texture-relations-host-probe.jar" -C "$out" .
jar tf "$root/build/texture-relations-host-probe.jar" | grep -Fx 'META-INF/turboism/i18n/messages.properties' >/dev/null
sha256sum "$root/build/texture-relations-host-probe.jar"
