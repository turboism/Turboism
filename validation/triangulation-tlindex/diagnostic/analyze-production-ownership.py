"""Stream JFR CPU/allocation ownership inside exact native command boundaries.

Diagnostic observations only: counts/weights are not exact CPU cost or live bytes.
"""
import argparse
from collections import Counter
import csv
import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess


EVENTS = 'jdk.ExecutionSample,jdk.NativeMethodSample,jdk.ObjectAllocationSample,jdk.GCHeapSummary,jdk.GarbageCollection,jdk.GCPhasePause,jdk.Deoptimization'
TRI = 'com.live2d.graphics3d.editableMesh.triangulation.'
INDEX = 'dev.turboism.adapter.cubism.mesh.TriangulationEdgeIndex'


class Pipe:
    def __init__(self, stream):
        self.stream = stream

    def open(self):
        return self.stream


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def frame_name(frame):
    method = frame.get('method') or {}
    return ((method.get('type') or {}).get('name', '').replace('/', '.') + '.'
            + method.get('name', '') + method.get('descriptor', ''))


def analyze(recording, boundaries, reader):
    with boundaries.open() as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    if [(r['phase'], int(r['operation'])) for r in rows] != [
            (phase, cycle) for cycle in (1, 2, 3)
            for phase in ('native-command-before', 'native-command-after')]:
        raise ValueError('six ordered native CPU boundaries required')
    windows = [(int(a['readEndEpochMillis']), int(b['readStartEpochMillis']))
               for a, b in zip(rows[::2], rows[1::2])]
    if any(a >= b for a, b in windows) or any(b >= c for (_, b), (c, _) in zip(windows, windows[1:])):
        raise ValueError('invalid native windows')
    counters = [{k: Counter() for k in ('events', 'cpuLeaf', 'cpuInclusive', 'cpuThread',
                                      'allocationClass', 'allocationSite', 'weights', 'cpuScopes')}
                for _ in windows]
    heaps = [[] for _ in windows]
    prior_heaps = [None for _ in windows]
    total_events = Counter()
    proc = subprocess.Popen(['jfr', 'print', '--json', '--stack-depth', '64', '--events', EVENTS,
                             str(recording)], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        for event in reader(Pipe(proc.stdout)):
            kind, value = event['type'], event['values']
            total_events[kind] += 1
            epoch = datetime.datetime.fromisoformat(value['startTime']).timestamp() * 1000
            if kind == 'jdk.GCHeapSummary':
                summary = {'epochMillis': epoch, 'gcId': value['gcId'], 'when': value['when'],
                           'usedHeapBytes': value['heapUsed'],
                           'committedHeapBytes': value['heapSpace']['committedSize']}
                for i, (lo, hi) in enumerate(windows):
                    if epoch < lo and (prior_heaps[i] is None or epoch > prior_heaps[i]['epochMillis']):
                        prior_heaps[i] = summary
                    elif lo <= epoch <= hi:
                        heaps[i].append(summary)
            for i, (lo, hi) in enumerate(windows):
                if not lo <= epoch <= hi:
                    continue
                c = counters[i]
                c['events'][kind] += 1
                trace = value.get('stackTrace') or {}
                frames = [frame_name(f) for f in trace.get('frames', [])]
                leaf = frames[0] if frames else '<unknown>'
                index = any(f.startswith(INDEX) for f in frames)
                triangulation = any(f.startswith(TRI) for f in frames)
                if kind in ('jdk.ExecutionSample', 'jdk.NativeMethodSample'):
                    c['cpuLeaf'][leaf] += 1
                    c['cpuInclusive'].update(set(frames))
                    thread = value.get('sampledThread') or value.get('eventThread') or {}
                    c['cpuThread'][thread.get('javaName') or thread.get('osName') or '<unknown>'] += 1
                    c['cpuScopes']['all'] += 1
                    c['cpuScopes']['indexInclusive'] += int(index)
                    c['cpuScopes']['triangulationInclusive'] += int(triangulation)
                    c['cpuScopes']['emptyStack'] += int(not frames)
                    c['cpuScopes']['truncatedStack'] += int(bool(trace.get('truncated')))
                elif kind == 'jdk.ObjectAllocationSample':
                    weight = value['weight']
                    if type(weight) is not int or weight < 0:
                        raise ValueError('invalid sampled allocation weight')
                    c['weights']['all'] += weight
                    c['weights']['indexInclusive'] += weight * int(index)
                    c['weights']['triangulationInclusive'] += weight * int(triangulation)
                    c['weights']['emptyStack'] += weight * int(not frames)
                    c['weights']['truncatedStack'] += weight * int(bool(trace.get('truncated')))
                    owner = (value.get('objectClass') or {}).get('name', '<unknown>').replace('/', '.')
                    c['allocationClass'][owner] += weight
                    c['allocationSite'][owner + ' <- ' + ' <- '.join(frames[:6])] += weight
        error = proc.stderr.read()
        if proc.wait(timeout=10) != 0:
            raise ValueError('JFR export failed: ' + error[-1000:])
    finally:
        if proc.poll() is None:
            proc.terminate()
            proc.wait(timeout=10)
    cycles = []
    for i, c in enumerate(counters):
        if not c['cpuScopes']['all'] or not c['events']['jdk.ObjectAllocationSample']:
            raise ValueError('native command lacks CPU or allocation samples')
        if sum(c['allocationClass'].values()) != c['weights']['all']:
            raise ValueError('allocation accounting mismatch')
        if sum(c['cpuLeaf'].values()) != c['cpuScopes']['all']:
            raise ValueError('CPU leaf accounting mismatch')
        cycles.append({'cycle': i + 1, 'startEpochMillis': windows[i][0], 'endEpochMillis': windows[i][1],
                       'eventCounts': dict(c['events']), 'cpuSampleScopes': dict(c['cpuScopes']),
                       'cpuLeafSites': c['cpuLeaf'].most_common(40),
                       'cpuInclusiveSites': c['cpuInclusive'].most_common(40),
                       'cpuSampleThreads': c['cpuThread'].most_common(),
                       'allocationEstimatedWeights': dict(c['weights']),
                       'allocationClassEstimatedWeights': c['allocationClass'].most_common(40),
                       'allocationSiteEstimatedWeights': c['allocationSite'].most_common(40),
                       'heapSummaries': heaps[i], 'nearestPriorHeapSummary': prior_heaps[i]})
    return {'status': 'PASS_NATIVE_WINDOW_JFR_OWNERSHIP_DIAGNOSTIC_ONLY',
            'wholeRecordingEventCounts': dict(total_events), 'cycles': cycles,
            'performanceAcceptance': 'NOT_GRANTED',
            'inputPins': {str(p): sha(p) for p in (recording, boundaries, Path(__file__))},
            'limitations': ['CPU samples count observed stacks, not exact CPU durations or causal savings.',
                            'Inclusive sites/scopes overlap; leaf sites alone partition CPU samples.',
                            'Allocation weights are sampled estimates and may straddle timestamp boundaries.',
                            'Missing/truncated/inlined frames limit owner attribution.',
                            'The same-thread native epoch windows exclude preparation/output work; concurrent threads remain included.',
                            'Sparse GC heap summaries are neither RSS nor allocation owners.',
                            'JFR changes runtime conditions; these jobs never replace failed production performance legs.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording', type=Path)
    parser.add_argument('boundaries', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    path = Path(__file__).resolve().parents[1] / 'analyze-resource-windows.py'
    spec = importlib.util.spec_from_file_location('resource', path)
    resource = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(resource)
    before = {str(p): sha(p) for p in (args.recording, args.boundaries)}
    report = analyze(args.recording, args.boundaries, resource.jfr_events)
    if before != {str(p): sha(p) for p in (args.recording, args.boundaries)}:
        raise ValueError('recording/boundaries changed during analysis')
    report['inputPins'][str(path)] = sha(path)
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')
    print(report['status'])
    for cycle in report['cycles']:
        print(cycle['cycle'], cycle['cpuSampleScopes'], cycle['allocationEstimatedWeights'])


if __name__ == '__main__':
    main()
