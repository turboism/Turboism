#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
shape_args=()
if [[ ${1:-} == '--shape' ]]; then
  shape_args+=("-Dturboism.validation.externalpsd.shapeRequired=true")
  shift
fi
[[ $# -eq 0 ]] || { echo 'Usage: test.sh [--shape]' >&2; exit 2; }
bash validation/external-psd-edit-host-probe/build.sh
id="$(bash scripts/dev/worktree-id.sh)"
shopt -s nullglob
sdk=("build/worktree/$id/sdk/libs/"sdk-*.jar)
out="$(mktemp -d)"
trap 'rm -rf "$out"' EXIT
javac --release 17 -Xlint:all -cp "${sdk[0]}:build/external-psd-edit-host-probe.jar" -d "$out" \
  validation/external-psd-edit-host-probe/test/dev/turboism/validation/externalpsd/PsdValidationContentTest.java \
  validation/external-psd-edit-host-probe/test/dev/turboism/validation/externalpsd/ExternalPsdEditHostProbeTest.java \
  validation/external-psd-edit-host-probe/test/dev/turboism/validation/externalpsd/ExactHostRowTargetTest.java \
  validation/external-psd-edit-host-probe/test/com/live2d/ui/treeTable/j.java
java -Djava.awt.headless=true -cp "$out:${sdk[0]}:build/external-psd-edit-host-probe.jar" \
  dev.turboism.validation.externalpsd.PsdValidationContentTest
java -Djava.awt.headless=true -cp "$out:${sdk[0]}:build/external-psd-edit-host-probe.jar" \
  dev.turboism.validation.externalpsd.ExternalPsdEditHostProbeTest
java -Djava.awt.headless=true "${shape_args[@]}" \
  -cp "$out:${sdk[0]}:build/external-psd-edit-host-probe.jar" \
  dev.turboism.validation.externalpsd.ExactHostRowTargetTest
python3 - "$id" <<'PY'
import pathlib, sys, zipfile, json
expected = {
    'turboism.cubism.model.read', 'turboism.cubism.model.write',
    'turboism.file.read', 'turboism.file.write', 'turboism.process.run',
    'turboism.cubism.model.observe', 'turboism.event.subscribe',
    'turboism.ui.file-chooser.request'}
with zipfile.ZipFile('build/external-psd-edit-host-probe.jar') as archive:
    names = archive.namelist()
    assert 'com/live2d/ui/treeTable/j.class' not in names
    assert not any(name.startswith('com/live2d/') for name in names)
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
