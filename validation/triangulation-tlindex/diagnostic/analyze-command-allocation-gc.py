#!/usr/bin/env python3
"""Bound JFR allocation samples and heap/GC observations to native command windows."""
import argparse
import collections
import csv
import datetime
import hashlib
import json
from pathlib import Path
import re
import runpy


def epoch(value):
    return datetime.datetime.fromisoformat(value).timestamp() * 1000


def seconds(value):
    match = re.fullmatch(r'PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?', value)
    if not match or not any(match.groups()):
        raise ValueError('unsupported JFR duration ' + value)
    hours, minutes, secs = match.groups()
    return int(hours or 0) * 3600 + int(minutes or 0) * 60 + float(secs or 0)


def windows(path):
    with path.open() as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    result = []
    for cycle in (1, 2, 3):
        bounds = []
        for phase in ('auto-connect-start', 'auto-connect-returned'):
            selected = [r for r in rows if r['phase'] == phase and int(r['operation']) == cycle]
            if len(selected) != 1:
                raise ValueError('missing/duplicate command boundary')
            bounds.append(int(selected[0]['epochMillis']))
        if bounds[0] >= bounds[1] or result and bounds[0] <= result[-1]['endEpochMillis']:
            raise ValueError('invalid/overlapping command windows')
        result.append(dict(cycle=cycle, startEpochMillis=bounds[0], endEpochMillis=bounds[1]))
    return result


def method(frame):
    value = frame.get('method') or {}
    return (value.get('type') or {}).get('name', '') + '.' + value.get('name', '') + value.get('descriptor', '')


def overlap(start, duration, command):
    return max(0, min(start + duration * 1000, command['endEpochMillis'])
               - max(start, command['startEpochMillis'])) / 1000


def summarize(events, commands):
    events = list(events)
    result = []
    for command in commands:
        counts = collections.Counter()
        weights = collections.Counter()
        classes = collections.Counter()
        sites = collections.Counter()
        bucket_classes = collections.Counter()
        deopts = collections.Counter()
        heaps, gc, pauses = [], [], []
        for event in events:
            kind, value = event['type'], event['values']
            start = epoch(value['startTime'])
            inside = command['startEpochMillis'] <= start <= command['endEpochMillis']
            if kind == 'jdk.GCPhasePause':
                clipped = overlap(start, seconds(value['duration']), command)
                if clipped:
                    pauses.append(clipped)
            if not inside:
                continue
            counts[kind] += 1
            if kind == 'jdk.ObjectAllocationSample':
                weight = value['weight']
                if weight < 0:
                    raise ValueError('negative allocation sample weight')
                frames = (value.get('stackTrace') or {}).get('frames', [])
                names = [method(frame) for frame in frames]
                if any(n.startswith('dev/turboism/adapter/cubism/mesh/TriangulationEdgeIndex.put(') for n in names):
                    bucket_classes[(value.get('objectClass') or {}).get('name', '<unknown>')] += weight
                weights['all'] += weight
                if not frames:
                    weights['emptyStack'] += weight
                if any(n.startswith('com/live2d/graphics3d/editableMesh/triangulation/') for n in names):
                    weights['triangulationStack'] += weight
                    classes[(value.get('objectClass') or {}).get('name', '<unknown>')] += weight
                    sites[' <- '.join(names[:6])] += weight
            elif kind == 'jdk.GCHeapSummary':
                heaps.append({'epochMillis': start, 'gcId': value['gcId'], 'when': value['when'],
                              'heapUsedBytes': value['heapUsed'],
                              'committedBytes': value['heapSpace']['committedSize']})
            elif kind == 'jdk.GarbageCollection':
                gc.append({'gcId': value['gcId'], 'name': value['name'], 'cause': value['cause'],
                           'durationSeconds': seconds(value['duration']),
                           'sumOfPausesSeconds': seconds(value['sumOfPauses'])})
            elif kind == 'jdk.Deoptimization':
                m = method({'method': value.get('method')})
                if m.startswith(('com/live2d/graphics3d/editableMesh/triangulation/',
                                 'dev/turboism/adapter/cubism/mesh/')):
                    deopts[(m, value.get('bci'), value.get('reason'))] += 1
        if sum(classes.values()) != weights['triangulationStack']:
            raise ValueError('allocation sample accounting mismatch')
        before = sorted((e['values'] for e in events if e['type'] == 'jdk.GCHeapSummary'
                         and epoch(e['values']['startTime']) < command['startEpochMillis']),
                        key=lambda v: epoch(v['startTime']))
        nearest = before[-1] if before else None
        result.append({**command, 'eventCounts': dict(counts),
                       'allocationSampleWeightBytes': dict(weights),
                       'triangulationAllocationClasses': dict(classes.most_common()),
                       'triangulationAllocationSites': dict(sites.most_common()),
                       'indexPutAllocationClassWeights': dict(bucket_classes.most_common()),
                       'gcEventsStartedInWindow': gc,
                       'gcPauseOverlapSeconds': sum(pauses),
                       'heapObservations': heaps,
                       'nearestPriorHeapObservation': None if nearest is None else {
                           'ageMillis': command['startEpochMillis'] - epoch(nearest['startTime']),
                           'when': nearest['when'], 'heapUsedBytes': nearest['heapUsed'],
                           'committedBytes': nearest['heapSpace']['committedSize']},
                       'triangulationDeoptimizations': [dict(method=m, bci=bci, reason=reason, count=count)
                                                      for (m, bci, reason), count in deopts.most_common()]})
    return result


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    reader_path = Path(__file__).resolve().parent.parent / 'analyze-resource-windows.py'
    reader = runpy.run_path(str(reader_path))['jfr_events']
    report = {'scope': 'Explicit native command epoch windows; JFR allocation weights and GC heap observations',
              'limitations': ['Allocation sample weights are estimates, not exact bytes/objects or retained heap.',
                              'Sample weights can cover allocation before the timestamp boundary.',
                              'GC heap observations are sparse and do not measure RSS or native memory.',
                              'Prior heap observation age is retained; it is not a command-start snapshot.',
                              'GC event duration is not CPU duration; pause overlap uses GCPhasePause only.',
                              'Single diagnostic pair cannot establish causal regression or stable benefit.'],
              'legs': {}, 'pins': {str(reader_path): sha(reader_path), str(Path(__file__)): sha(Path(__file__))}}
    for leg in ('baseline', 'candidate'):
        events = args.root / 'allocation-gc-audit-r1' / (leg + '-events.json')
        markers = args.root / 'native5303-pair1' / leg / 'native-evidence/resource-windows.tsv'
        recording = markers.parent / 'atlas-profiling.jfr'
        report['legs'][leg] = summarize(reader(events), windows(markers))
        report['pins'].update({str(p): sha(p) for p in (events, markers, recording)})
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')
    for leg, cycles in report['legs'].items():
        print(leg, json.dumps([{'cycle': c['cycle'], 'weights': c['allocationSampleWeightBytes'],
                               'pauseSeconds': c['gcPauseOverlapSeconds'],
                               'gcCount': len(c['gcEventsStartedInWindow']),
                               'classes': list(c['triangulationAllocationClasses'].items())[:5]}
                              for c in cycles]))


if __name__ == '__main__':
    main()
