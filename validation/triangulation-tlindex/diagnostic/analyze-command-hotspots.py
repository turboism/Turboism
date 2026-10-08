#!/usr/bin/env python3
"""Rank separate Java/native JFR leaves within explicit native command windows."""
import argparse
import collections
import csv
import datetime
import hashlib
import json
from pathlib import Path
import runpy


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def method(frame):
    value = frame.get('method') or {}
    return (value.get('type') or {}).get('name', '') + '.' + value.get('name', '') + value.get('descriptor', '')


def analyze(root, leg):
    samples = root / 'hotspots' / (leg + '-samples.json')
    markers = root / 'native5303-pair1' / leg / 'native-evidence/resource-windows.tsv'
    reader_path = Path(__file__).resolve().parent.parent / 'analyze-resource-windows.py'
    reader = runpy.run_path(str(reader_path))['jfr_events']
    with markers.open() as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    windows = []
    for operation in (1, 2, 3):
        start = [r for r in rows if r['phase'] == 'auto-connect-start' and int(r['operation']) == operation]
        end = [r for r in rows if r['phase'] == 'auto-connect-returned' and int(r['operation']) == operation]
        if len(start) != 1 or len(end) != 1:
            raise ValueError('missing/duplicate operation boundaries')
        a, b = int(start[0]['epochMillis']), int(end[0]['epochMillis'])
        if a >= b or (windows and a <= windows[-1]['endEpochMillis']):
            raise ValueError('invalid operation windows')
        windows.append({'operation': operation, 'startEpochMillis': a, 'endEpochMillis': b})
    java, native, paths, totals = (collections.Counter() for _ in range(4))
    for event in reader(samples):
        kind = event['type']
        if kind not in ('jdk.ExecutionSample', 'jdk.NativeMethodSample'):
            continue
        value = event['values']
        epoch = datetime.datetime.fromisoformat(value['startTime']).timestamp() * 1000
        if not any(w['startEpochMillis'] <= epoch <= w['endEpochMillis'] for w in windows):
            continue
        totals[kind + 'InCommandWindows'] += 1
        trace = value.get('stackTrace') or {}
        frames = trace.get('frames', [])
        if not frames:
            totals[kind + 'EmptyStack'] += 1
            continue
        names = [method(frame) for frame in frames]
        if not any(n.startswith('com/live2d/graphics3d/editableMesh/triangulation/') for n in names):
            continue
        totals[kind + 'TriangulationStack'] += 1
        if trace.get('truncated'):
            totals[kind + 'TriangulationTruncatedStack'] += 1
        if kind == 'jdk.NativeMethodSample':
            native[names[0]] += 1
        else:
            java[(names[0], frames[0].get('bytecodeIndex', -1))] += 1
            paths[' <- '.join(names[:6])] += 1
    if sum(java.values()) != totals['jdk.ExecutionSampleTriangulationStack']:
        raise ValueError('Java sample accounting mismatch')
    if sum(native.values()) != totals['jdk.NativeMethodSampleTriangulationStack']:
        raise ValueError('native sample accounting mismatch')
    return {'windows': windows, 'counts': dict(totals),
            'javaLeafBci': [{'method': name, 'bci': bci, 'samples': count} for (name, bci), count in java.most_common()],
            'nativeLeaves': dict(native.most_common()), 'javaLeafCallPaths': dict(paths.most_common()),
            'pins': {str(p): sha(p) for p in (samples, markers, reader_path)}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args()
    result = {'scope': 'Same-recording epoch time vs explicit three native command windows; triangulation-stack condition',
              'limitations': ['Sample counts are not calls, CPU duration or removable-time estimates.',
                              'Java and native samples must not be added to infer CPU proportions.',
                              'JIT inlining can attribute native callees to caller BCIs; map exact installed method bytes.',
                              'Truncated or empty stacks limit attribution; separate counters retain those observations.'],
              'analyzerSha256': sha(Path(__file__)),
              'legs': {leg: analyze(args.root, leg) for leg in ('baseline', 'candidate')}}
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(json.dumps({leg: data['counts'] for leg, data in result['legs'].items()}, indent=2))


if __name__ == '__main__':
    main()
