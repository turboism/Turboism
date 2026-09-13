#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
bash validation/external-psd-edit-host-probe/build.sh
id="$(bash scripts/dev/worktree-id.sh)"
shopt -s nullglob
sdk=("build/worktree/$id/sdk/libs/"sdk-*.jar)
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 17 -cp "${sdk[0]}:build/external-psd-edit-host-probe.jar" -d "$out" \
  validation/external-psd-edit-host-probe/test/dev/turboism/validation/externalpsd/ExternalPsdEditHostProbeTest.java
java -cp "$out:${sdk[0]}:build/external-psd-edit-host-probe.jar" \
  dev.turboism.validation.externalpsd.ExternalPsdEditHostProbeTest
python3 - "$id" <<'PY'
import pathlib, sys, zipfile, json
expected = {
    'turboism.cubism.model.read', 'turboism.cubism.model.write',
    'turboism.file.read', 'turboism.file.write', 'turboism.process.run',
    'turboism.cubism.model.observe', 'turboism.ui.file-chooser.request'}
with zipfile.ZipFile('build/external-psd-edit-host-probe.jar') as archive:
    metadata = json.loads(archive.read('META-INF/turboism/plugin.json'))
    assert {p['id'] for p in metadata['permissions']} == expected
    assert metadata['entrypoints'] == [
        'dev.turboism.validation.externalpsd.ExternalPsdEditHostProbe']
print('PASS: probe declares only the pipeline permissions it exercises')
bundle = pathlib.Path('build/preview') / sys.argv[1]
if bundle.is_dir():
    for jar in bundle.rglob('*.jar'):
        with zipfile.ZipFile(jar) as archive:
            assert not any('dev/turboism/validation/externalpsd/' in n
                           for n in archive.namelist()), jar
    print('PASS: probe absent from production preview artifacts')
else:
    print('SKIP: preview bundle not built in this worktree')
PY
