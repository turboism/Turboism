#!/usr/bin/env python3
"""Stream large allocation exports; preserve the existing diagnostic weight accounting."""
import argparse
import collections
import hashlib
import json
from pathlib import Path
import runpy

HELPERS = runpy.run_path(str(Path(__file__).with_name('analyze-command-allocation-gc.py')))
MEMORY = runpy.run_path(str(Path(__file__).with_name('analyze-memory-window-gc.py')))


def summarize(events, windows):
    totals = [dict(counts=collections.Counter(), weights=collections.Counter(),
                   classes=collections.Counter(), sites=collections.Counter(),
                   buckets=collections.Counter()) for _ in windows]
    # Heap/GC/deoptimization events are small; allocation stack objects are never retained.
    other = []
    for event in events:
        if event['type'] != 'jdk.ObjectAllocationSample':
            if event['type'] not in ('jdk.GCHeapSummary', 'jdk.GarbageCollection',
                                     'jdk.GCPhasePause', 'jdk.Deoptimization'):
                raise ValueError('unsupported event type in allocation export')
            other.append(event)
            continue
        value = event['values']
        stamp = HELPERS['epoch'](value['startTime'])
        weight = value['weight']
        if weight < 0:
            raise ValueError('negative allocation sample weight')
        names = [HELPERS['method'](f) for f in (value.get('stackTrace') or {}).get('frames', [])]
        cls = (value.get('objectClass') or {}).get('name', '<unknown>')
        triangulation = any(n.startswith('com/live2d/graphics3d/editableMesh/triangulation/') for n in names)
        put = any(n.startswith('dev/turboism/adapter/cubism/mesh/TriangulationEdgeIndex.put(') for n in names)
        for window, total in zip(windows, totals):
            # Same inclusive convention as the inherited analyzer, not a disjoint partition.
            if not window['startEpochMillis'] <= stamp <= window['endEpochMillis']:
                continue
            total['counts'][event['type']] += 1
            total['weights']['all'] += weight
            if not names:
                total['weights']['emptyStack'] += weight
            if triangulation:
                total['weights']['triangulationStack'] += weight
                total['classes'][cls] += weight
                total['sites'][' <- '.join(names[:6])] += weight
            if put:
                total['buckets'][cls] += weight
    result = HELPERS['summarize'](other, windows)
    for row, total in zip(result, totals):
        counts = collections.Counter(row['eventCounts'])
        counts.update(total['counts'])
        row['eventCounts'] = dict(counts)
        row['allocationSampleWeightBytes'] = dict(total['weights'])
        row['triangulationAllocationClasses'] = dict(total['classes'].most_common())
        row['triangulationAllocationSites'] = dict(total['sites'].most_common())
        row['indexPutAllocationClassWeights'] = dict(total['buckets'].most_common())
        if sum(total['classes'].values()) != total['weights']['triangulationStack']:
            raise ValueError('allocation sample accounting mismatch')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--events', type=Path, required=True)
    parser.add_argument('--markers', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    reader_path = Path(__file__).resolve().parent.parent / 'analyze-resource-windows.py'
    reader = runpy.run_path(str(reader_path))['jfr_events']
    bounds = MEMORY['windows'](args.markers)
    report = {'scope': 'Sparse allocation weights; not performance acceptance',
              'windows': summarize(reader(args.events), bounds),
              'limitations': ['Weights are estimates, not exact allocations, retention or savings.',
                              'Inclusive boundaries can count a shared timestamp in two windows.',
                              'Memory scales with unique sites and non-allocation events, not allocation event count.'],
              'pins': {}}
    for p in (args.events, args.markers, Path(__file__), reader_path,
              Path(__file__).with_name('analyze-command-allocation-gc.py'),
              Path(__file__).with_name('analyze-memory-window-gc.py')):
        with p.open('rb') as stream:
            report['pins'][str(p)] = hashlib.file_digest(stream, 'sha256').hexdigest()
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')


if __name__ == '__main__':
    main()
