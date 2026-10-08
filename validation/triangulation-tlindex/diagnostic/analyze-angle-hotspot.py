#!/usr/bin/env python3
"""T055: pin and separate real JFR native angle samples by explicit command windows."""
import argparse
import collections
import csv
import datetime
import hashlib
import json
from pathlib import Path
import runpy


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def analyze(samples, markers):
    reader = runpy.run_path(str(Path(__file__).resolve().parent.parent /
                               'analyze-resource-windows.py'))['jfr_events']
    with markers.open() as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    windows = []
    for operation in range(1, 4):
        start = [r for r in rows if r['phase'] == 'auto-connect-start' and int(r['operation']) == operation]
        end = [r for r in rows if r['phase'] == 'auto-connect-returned' and int(r['operation']) == operation]
        if len(start) != 1 or len(end) != 1:
            raise ValueError('missing/duplicate native command marker')
        a, b = int(start[0]['epochMillis']), int(end[0]['epochMillis'])
        if a >= b or (windows and windows[-1][2] >= a):
            raise ValueError('invalid command windows')
        windows.append((operation, a, b))
    counts = collections.defaultdict(collections.Counter)
    callers = collections.defaultdict(collections.Counter)
    for event in reader(samples):
        if event['type'] not in ('jdk.NativeMethodSample', 'jdk.ExecutionSample'):
            continue
        value = event['values']
        epoch = datetime.datetime.fromisoformat(value['startTime']).timestamp() * 1000
        bucket = next((str(op) for op, a, b in windows if a <= epoch <= b), 'outside')
        frames = (value.get('stackTrace') or {}).get('frames', [])
        names = []
        for frame in frames:
            method = frame.get('method') or {}
            names.append((method.get('type') or {}).get('name', '') + '.' +
                         method.get('name', '') + method.get('descriptor', ''))
        if not any(n.startswith('com/live2d/graphics3d/editableMesh/triangulation/') for n in names):
            continue
        kind = event['type']
        counts[bucket][kind] += 1
        if kind == 'jdk.NativeMethodSample' and names[0] == 'java/lang/StrictMath.atan2(DD)D':
            counts[bucket]['nativeAtan2Leaf'] += 1
            callers[bucket][' <- '.join(names[:5])] += 1
            if any(n == 'com/live2d/graphics3d/editableMesh/triangulation/h.d()V' for n in names):
                counts[bucket]['nativeAtan2WithHD'] += 1
    return dict(windows=[dict(operation=op, start_epoch_ms=a, end_epoch_ms=b) for op, a, b in windows],
                counts={key: dict(value) for key, value in counts.items()},
                native_angle_paths={key: dict(value) for key, value in callers.items()},
                pins={str(samples): digest(samples), str(markers): digest(markers)})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=Path('build/t053-local-builder-r1'))
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    result = dict(scope='Same-JVM event wall timestamp vs explicit auto-connect start/returned markers; '
                        'native and Java samples remain separate, counts are not CPU or calls', legs={})
    for leg in ('baseline', 'candidate'):
        result['legs'][leg] = analyze(args.root / 'hotspots' / (leg + '-samples.json'),
                                    args.root / 'native5303-pair1' / leg / 'native-evidence' /
                                    'resource-windows.tsv')
    result['analyzer_sha256'] = digest(Path(__file__))
    args.output.write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps({leg: data['counts'] for leg, data in result['legs'].items()}, indent=2))


if __name__ == '__main__':
    main()
