#!/usr/bin/env python3
"""Compile isolated source variants and run synthetic benchmarks. Never starts a host.
Generated copies stay under build; production sources/artifacts are never edited.
"""
import hashlib
import argparse
import json
import pathlib
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--baseline-dir', type=pathlib.Path, required=True,
                    help='Frozen pre-preflight source directory, not current production sources')
parser.add_argument('--output-dir', type=pathlib.Path, required=True,
                    help='New directory; existing evidence is never overwritten')
args = parser.parse_args()
SOURCE = args.baseline_dir.resolve()
OUT = args.output_dir.resolve()
assert hashlib.sha256((SOURCE / 'CurrentPageTextureAtlasPlanner.java').read_bytes()).hexdigest() == '936621a3372505bc8e954d79b02eb272905bde781f29cf995c2cd9d865120a51', 'Unexpected baseline planner'
OUT.mkdir(parents=True, exist_ok=False)
worktree = subprocess.check_output(['bash', 'scripts/dev/worktree-id.sh'], cwd=ROOT, text=True).strip()
sdk, = (ROOT / f'build/worktree/{worktree}/sdk/libs').glob('*.jar')
planner = (SOURCE / 'CurrentPageTextureAtlasPlanner.java').read_text()
regions = (SOURCE / 'CurrentPageRegions.java').read_text()
harness = pathlib.Path(__file__).with_name('OfflinePolicyBench.java')
sha = lambda data: hashlib.sha256(data).hexdigest()
inventory = {'sdk': sha(sdk.read_bytes()), 'planner': sha(planner.encode()),
             'regions': sha(regions.encode()), 'harness': sha(harness.read_bytes()),
             'java': subprocess.check_output(['java', '-version'], stderr=subprocess.STDOUT, text=True),
             'variants': {}}
for variant, threshold, multi in [('baseline',16,False), ('multi',16,True), ('threshold32',32,False), ('threshold64',64,False), ('preflight32',16,False)]:
    folder = OUT / variant
    folder.mkdir(exist_ok=True)
    modified = planner
    if multi:
        needle = ': pack(ordered, constraints, trialScale, allRequired);'
        assert modified.count(needle) == 1
        modified = modified.replace(needle, ': bestPacking(inputs, constraints, trialScale, allRequired);')
    if variant == 'preflight32':
        needle = '        if (parallel) {\n            final var regions'
        assert modified.count(needle) == 1
        modified = modified.replace(needle, '''        final Packed preflight = parallel && items.size() < 32
            ? bestPacking(items, c, scale, allRequired) : null;
        if (preflight != null && preflight.placements.size() == items.size()) return preflight;
        if (parallel) {
            final var regions''')
        modified = modified.replace('final Packed serial = bestPacking(items, c, scale, allRequired);',
            'final Packed serial = preflight != null ? preflight : bestPacking(items, c, scale, allRequired);')
        modified = modified.replace('return bestPacking(items, c, scale, allRequired);',
            'return preflight != null ? preflight : bestPacking(items, c, scale, allRequired);')
    assert regions.count('items.size() < 16') == 1
    r = regions.replace('items.size() < 16', f'items.size() < {threshold}')
    (folder / 'CurrentPageTextureAtlasPlanner.java').write_text(modified)
    (folder / 'CurrentPageRegions.java').write_text(r)
    inventory['variants'][variant] = {'threshold':threshold, 'multi':multi,
        'planner':sha(modified.encode()), 'regions':sha(r.encode())}
    subprocess.run(['javac','--release','17','-cp',str(sdk),'-d',str(folder),
        str(folder/'CurrentPageTextureAtlasPlanner.java'),str(folder/'CurrentPageRegions.java'),str(harness)],check=True)
# All compilations finish before timed JVM runs. Same heap/pool, independent forks, no host launch.
(OUT/'inventory.json').write_text(json.dumps(inventory,indent=2)+'\n')
for repeat, variants in enumerate([list(inventory['variants']),list(reversed(inventory['variants']))]):
    for variant in variants:
        print('Running', repeat, variant, flush=True)
        with (OUT/f'{variant}-{repeat}.csv').open('w') as result:
            subprocess.run(['java','-Xms256m','-Xmx512m','-Djava.util.concurrent.ForkJoinPool.common.parallelism=4',
                '-cp',f'{OUT/variant}:{sdk}','dev.turboism.validation.texture.OfflinePolicyBench',variant],
                stdout=result,check=True,timeout=240)
print(OUT)
