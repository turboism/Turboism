#!/usr/bin/env bash
# Offline wrapper/auxiliary-Agent contract gate; requires freshly packaged Atlas artifacts.
# No submit, premain, Swing, Wine or host launch.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$root"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
wrapper="$root/scripts/preview/run-atlas-host-validation.sh"
for spec in geometry-2500-both geometry-2499-new geometry-100-both ui-circle-2499-native ui-geometry-100-both; do
  if bash "$wrapper" 5303 offline "$spec" --prepare-dir "$work" > "$work/reject.log" 2>&1; then
    echo "Unexpected admission: $spec" >&2; exit 1
  fi
done
if bash "$wrapper" 5303 offline geometry-100-new --jvm-option arbitrary > "$work/reject.log" 2>&1; then
  echo 'Arbitrary override admitted' >&2; exit 1
fi
bash "$wrapper" 5303 offline geometry-100-new --prepare-dir "$work"
python3 - "$work/runner-request.json" <<'PY'
import json, sys
x = json.load(open(sys.argv[1]))
a = x['argv']
assert a[a.index('--version') + 1] == '5303'
assert '--fixture-name' in a, 'Missing explicit fixture suffix: probe would reject manager run-ID-only filename'
assert a[a.index('--fixture-name') + 1] == 'atlas_mapping_geometry_100.cmo3'
assert a.count('--plugin') == 1 and a.count('--aux-agent') == 1
assert '--require-fixture-unchanged' in a
assert not any(v in a for v in ('--client-script', '--remote-pre-launch', '--remote-post-launch', '--remote-pre-cleanup'))
assert x.get('environment', {}) == {}
print('PASS: fixed Atlas normalized request and no hook/client dependencies')
PY
for implementation in native new; do
  bash "$wrapper" 5303 offline "geometry-2500-$implementation" --prepare-dir "$work"
  python3 - "$work/runner-request.json" "$implementation" <<'PY'
import json, sys
x = json.load(open(sys.argv[1])); a = x['argv']
assert a[a.index('--fixture-name') + 1] == 'atlas_mapping_geometry_2500.cmo3'
assert a[a.index('--fixture-sha256') + 1] == '4bb38d8073cf339b32047bf186514dc7d3709cbfb90dbe665b0a5b0a76daf24b'
assert a[a.index('--result-timeout') + 1] == '21900'
assert '-Dturboism.validation.atlas.count=2500' in a
assert '-Dturboism.validation.atlas.implementation=' + sys.argv[2] in a
assert a.count('--plugin') == a.count('--aux-agent') == 1
assert '--require-fixture-unchanged' in a
assert not any(flag in a for flag in ('--client-script', '--remote-pre-launch', '--remote-post-launch', '--remote-pre-cleanup'))
print('PASS: final 2500 immutable fixture/implementation/long-timeout preparation', sys.argv[2])
PY
done
for dataset in circle geometry; do
  for count in 100 500 1000 2500; do
    for implementation in native new; do
      bash "$wrapper" 5303 offline "ui-$dataset-$count-$implementation" --prepare-dir "$work"
      python3 - "$work/runner-request.json" "$dataset" "$count" "$implementation" <<'PY'
import hashlib, json, pathlib, sys
x = json.load(open(sys.argv[1])); a = x['argv']
dataset, count, implementation = sys.argv[2:]
assert a[a.index('--result-timeout') + 1] == '1800'
for key, value in [('dataset', dataset), ('count', count), ('implementation', implementation), ('uiTiming', 'true')]:
    assert f'-Dturboism.validation.atlas.{key}={value}' in a
suffix = f'atlas_mapping_{"geometry_" if dataset == "geometry" else ""}{count}.cmo3'
assert a[a.index('--fixture-name') + 1] == suffix
fixture = pathlib.Path(a[a.index('--fixture-local') + 1])
assert fixture.name == suffix
assert hashlib.sha256(fixture.read_bytes()).hexdigest() == a[a.index('--fixture-sha256') + 1]
assert a.count('--plugin') == a.count('--aux-agent') == 1
assert '--require-fixture-unchanged' in a
assert not any(flag in a for flag in ('--client-script', '--remote-pre-launch', '--remote-post-launch', '--remote-pre-cleanup'))
print('PASS: fixed UI fixture, mode and 1800s result budget', dataset, count, implementation)
PY
    done
  done
done
id="$(bash scripts/dev/worktree-id.sh)"
cp="build/preview/$id/turboism-agent.jar:build/atlas-queue-probe/atlas-queue-probe.jar"
javac --release 17 -cp "$cp" -d "$work" validation/texture-atlas-current-page/AtlasQueueProbeTest.java
java -cp "$work:$cp" dev.turboism.validation.texture.AtlasQueueProbeTest
