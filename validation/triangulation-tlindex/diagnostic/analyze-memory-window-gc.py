#!/usr/bin/env python3
"""Correlate sparse JFR heap/GC events with frozen memory observation windows."""
import argparse
import csv
import hashlib
import json
from pathlib import Path
import runpy

HELPERS = runpy.run_path(str(Path(__file__).with_name('analyze-command-allocation-gc.py')))
epoch, seconds, overlap = (HELPERS[n] for n in ('epoch', 'seconds', 'overlap'))


def windows(markers):
    with markers.open() as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    by_key = {}
    previous = None
    for row in rows:
        key = (row['phase'], int(row['operation']))
        stamp = int(row['epochMillis'])
        if key in by_key or previous is not None and stamp < previous:
            raise ValueError('duplicate or decreasing marker')
        by_key[key] = stamp
        previous = stamp
    result = []

    def add(phase, cycle, start, end):
        a, b = by_key[(start, cycle)], by_key[(end, cycle)]
        if a >= b:
            raise ValueError('nonpositive window')
        result.append(dict(phase=phase, cycle=cycle, startEpochMillis=a, endEpochMillis=b))

    add('baseline', 0, 'mesh-baseline-start', 'mesh-baseline-end')
    for cycle in (1, 2, 3):
        add('command', cycle, 'auto-connect-start', 'auto-connect-returned')
        add('post-return', cycle, 'auto-connect-returned', 'mesh-retained-start')
        add('retained', cycle, 'mesh-retained-start', 'mesh-retained-end')
    if any(a['endEpochMillis'] > b['startEpochMillis'] for a, b in zip(result, result[1:])):
        raise ValueError('overlapping windows')
    return result


def summarize(events, bounds):
    # Only small heap/GC exports belong here; allocation exports have a different analyzer.
    allowed = {'jdk.GCHeapSummary', 'jdk.GarbageCollection', 'jdk.GCPhasePause'}
    records = []
    for event in events:
        if event['type'] not in allowed:
            raise ValueError('unsupported event type')
        value = event['values']
        records.append((epoch(value['startTime']), event['type'], value))
    records.sort(key=lambda r: r[0])
    heaps = []
    for stamp, kind, value in records:
        if kind == 'jdk.GCHeapSummary':
            used, committed = value['heapUsed'], value['heapSpace']['committedSize']
            if used < 0 or committed < used:
                raise ValueError('invalid heap values')
            heaps.append(dict(epochMillis=stamp, gcId=value['gcId'], when=value['when'],
                              usedBytes=used, committedBytes=committed))
    result = []
    for bound in bounds:
        start, end = bound['startEpochMillis'], bound['endEpochMillis']
        if start >= end:
            raise ValueError('nonpositive window')
        # Half-open windows: adjacent command/post-return boundaries count once.
        inside = [h for h in heaps if start <= h['epochMillis'] < end]
        before = [h for h in heaps if h['epochMillis'] < start]
        after = [h for h in heaps if h['epochMillis'] >= end]
        prior = None if not before else {**before[-1], 'ageMillis': start-before[-1]['epochMillis']}
        following = None if not after else {**after[0], 'delayMillis': after[0]['epochMillis']-end}
        gc, pauses = [], []
        for stamp, kind, value in records:
            if kind not in ('jdk.GarbageCollection', 'jdk.GCPhasePause'):
                continue
            duration = seconds(value['duration'])
            if duration < 0:
                raise ValueError('negative duration')
            clipped = overlap(stamp, duration, bound)
            if kind == 'jdk.GCPhasePause' and clipped:
                pauses.append(dict(gcId=value['gcId'], overlapSeconds=clipped))
            elif kind == 'jdk.GarbageCollection' and (clipped or start <= stamp < end):
                gc.append(dict(gcId=value['gcId'], name=value['name'], cause=value['cause'],
                               startEpochMillis=stamp, durationSeconds=duration,
                               overlapSeconds=clipped, startsInside=start <= stamp < end))
        result.append({**bound, 'heapObservations': inside, 'nearestPriorHeapObservation': prior,
                       'nearestFollowingHeapObservation': following,
                       'gcEventsOverlappingOrStartingInside': gc, 'pauseOverlaps': pauses,
                       'gcPauseOverlapSeconds': sum(p['overlapSeconds'] for p in pauses)})
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--markers', type=Path, required=True)
    parser.add_argument('--events', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    reader_path = Path(__file__).resolve().parent.parent / 'analyze-resource-windows.py'
    reader = runpy.run_path(str(reader_path))['jfr_events']
    report = {'status': 'DIAGNOSTIC_ONLY_NOT_ACCEPTANCE',
              'windows': summarize(reader(args.events), windows(args.markers)),
              'limitations': ['Heap summaries are sparse observations, not boundary snapshots.',
                              'Used heap is not live retained heap; committed heap is not RSS.',
                              'GC elapsed duration and pause overlap are not CPU measurements.',
                              'Temporal association does not establish a Java/native/GC memory cause.'],
              'pins': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in
                       (args.markers, args.events, Path(__file__), reader_path,
                        Path(__file__).with_name('analyze-command-allocation-gc.py'))}}
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')


if __name__ == '__main__':
    main()
