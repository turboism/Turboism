#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
bash validation/texture-relations-host-probe/build.sh
id="$(bash scripts/dev/worktree-id.sh)"
shopt -s nullglob
sdk=("build/worktree/$id/sdk/libs/"sdk-*.jar)
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 17 -cp "${sdk[0]}:build/texture-relations-host-probe.jar" -d "$out" \
  validation/texture-relations-host-probe/test/dev/turboism/validation/textures/TextureRelationsHostProbeTest.java
java -cp "$out:${sdk[0]}:build/texture-relations-host-probe.jar" \
  dev.turboism.validation.textures.TextureRelationsHostProbeTest
python3 - "$id" <<'PY'
import pathlib, sys, zipfile
bundle=pathlib.Path('build/preview')/sys.argv[1]
agent=bundle/'turboism-agent.jar'
assert agent.is_file(), 'Build previewBundle before the packaging guard'
for jar in bundle.rglob('*.jar'):
    with zipfile.ZipFile(jar) as archive:
        assert not any('dev/turboism/validation/textures/' in n for n in archive.namelist()), jar
print('PASS: smoke probe absent from production preview artifacts')
PY
