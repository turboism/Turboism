"""Audit task-local JVM context; surviving thread counters do not establish whole-process attribution."""
import argparse
import csv
import hashlib
import json
from pathlib import Path


def required(ok, message):
    if not ok:
        raise ValueError(message)


def snapshots(rows):
    grouped = {}
    for row in rows:
        key = (row['phase'], int(row['operation']))
        group = grouped.setdefault(key, [])
        group.append(row)
    expected = {('mesh-baseline-start', 0), ('mesh-baseline-end', 0)}
    expected |= {(phase, cycle) for cycle in (1, 2, 3) for phase in (
        'command-dispatch-start', 'command-observation-end', 'mesh-retained-start',
        'mesh-retained-end', 'native-command-before', 'native-command-after')}
    required(set(grouped) == expected, 'twenty exact context snapshots required')
    for key, group in grouped.items():
        spans = {tuple(row[n] for n in ('epochStartMillis', 'epochEndMillis', 'nanoStart', 'nanoEnd')) for row in group}
        required(len(spans) == 1, 'mixed capture identities')
        start, end, ns, ne = map(int, next(iter(spans)))
        required(end >= start and ne >= ns, 'negative capture span')
        identities = [(row['kind'], row['name'], row['id']) for row in group]
        required(len(identities) == len(set(identities)), 'duplicate context metric identity')
        required(sum(row['kind'] == 'heap' for row in group) == 1, 'heap coverage')
        required(sum(row['kind'] == 'nonheap' for row in group) == 1, 'nonheap coverage')
        required(any(row['kind'] == 'gc' for row in group), 'GC coverage')
        required(any(row['kind'] == 'thread' for row in group), 'Java thread coverage')
        for row in group:
            if row['kind'] in ('heap', 'nonheap', 'pool') and row['state'] != 'UNAVAILABLE':
                required(0 <= int(row['first']) <= int(row['second']), 'used/committed invariant')
    return grouped


def thread_delta(before, after):
    left = {int(row['id']): row for row in before if row['kind'] == 'thread'}
    right = {int(row['id']): row for row in after if row['kind'] == 'thread'}
    known = []; unavailable = []
    for identity in sorted(left.keys() & right.keys()):
        a, b = left[identity], right[identity]
        if min(int(a['first']), int(b['first'])) < 0 or a['name'] != b['name']:
            unavailable.append(identity)
            continue
        delta = int(b['first']) - int(a['first'])
        required(delta >= 0, 'surviving thread CPU counter regressed')
        known.append(dict(id=identity, name=b['name'], cpuDeltaNanos=delta,
                          userDeltaNanos=None if min(int(a['second']), int(b['second'])) < 0 else int(b['second']) - int(a['second'])))
    return dict(survivingObservedThreads=sorted(known, key=lambda row: row['cpuDeltaNanos'], reverse=True),
                observedSurvivingThreadCpuSeconds=sum(row['cpuDeltaNanos'] for row in known) / 1e9,
                newlyObservedThreadIds=sorted(right.keys() - left.keys()),
                disappearedThreadIds=sorted(left.keys() - right.keys()),
                unavailableOrRenamedThreadIds=unavailable)


def counters(before, after, kind):
    a = {row['name']: row for row in before if row['kind'] == kind}
    b = {row['name']: row for row in after if row['kind'] == kind}
    required(set(a) == set(b), kind + ' metric identity changed')
    result = []
    for name in a:
        deltas = {}
        for field in ('first', 'second'):
            start, end = int(a[name][field]), int(b[name][field])
            value = None if min(start, end) < 0 else end - start
            required(value is None or value >= 0, kind + ' counter regressed')
            deltas[field] = value
        result.append(dict(name=name, **deltas))
    return result


def analyze(context, cpu):
    groups = snapshots(context)
    required([(row['phase'], int(row['operation'])) for row in cpu] ==
             [(phase, cycle) for cycle in (1, 2, 3) for phase in ('native-command-before', 'native-command-after')], 'six CPU boundaries')
    cycles = []
    for cycle in (1, 2, 3):
        before, after = groups[('native-command-before', cycle)], groups[('native-command-after', cycle)]
        a, b = cpu[(cycle - 1) * 2:cycle * 2]
        required(int(before[0]['nanoEnd']) <= int(a['readStartNanos']) <= int(a['readEndNanos']), 'context before counter interval')
        required(int(after[0]['nanoStart']) >= int(b['readEndNanos']) >= int(b['readStartNanos']), 'context after counter interval')
        cycles.append(dict(operation=cycle, gcCountAndElapsedMillisDeltas=counters(before, after, 'gc'),
                           compilerElapsedMillisDeltas=counters(before, after, 'compiler'),
                           threads=thread_delta(before, after),
                           beforeCaptureSpanNanos=int(before[0]['nanoEnd']) - int(before[0]['nanoStart']),
                           afterCaptureSpanNanos=int(after[0]['nanoEnd']) - int(after[0]['nanoStart'])))
    memory = []
    for (phase, operation), rows in groups.items():
        memory.append(dict(phase=phase, operation=operation, epochStartMillis=int(rows[0]['epochStartMillis']),
                           epochEndMillis=int(rows[0]['epochEndMillis']),
                           metrics=[dict(kind=row['kind'], name=row['name'], usedBytes=int(row['first']),
                                         committedBytes=int(row['second']), maximumBytes=int(row['third']), state=row['state'])
                                    for row in rows if row['kind'] in ('heap', 'nonheap', 'pool')],
                           buffers=[row for row in rows if row['kind'] == 'buffer']))
    return dict(status='PASS_JVM_CONTEXT_STRUCTURE_COUNTER_SCOPE_NOT_PERFORMANCE_ACCEPTANCE', cycles=cycles,
                memorySnapshots=memory, snapshotCount=len(groups),
                limitations=['Diagnostic enumeration surrounds and perturbs the workload; formal T097 failures unchanged.',
                             'Thread delta spans are wider than process counter intervals and counters are read sequentially.',
                             'New/exited/unavailable/renamed Java threads and JVM internal threads are not fully attributed.',
                             'GC and compilation counters are elapsed milliseconds, not CPU; no residual is labelled GC CPU.',
                             'No forced GC, monitoring enablement, stack sampling, or production acceptance.'])


def selfcheck():
    a = [dict(kind='thread', id='1', name='EDT', first='100', second='80'),
         dict(kind='thread', id='2', name='exited', first='30', second='20')]
    b = [dict(kind='thread', id='1', name='EDT', first='150', second='120'),
         dict(kind='thread', id='3', name='new', first='90', second='60')]
    delta = thread_delta(a, b)
    assert delta['observedSurvivingThreadCpuSeconds'] == 50 / 1e9
    assert delta['newlyObservedThreadIds'] == [3] and delta['disappearedThreadIds'] == [2]
    assert thread_delta([dict(a[0], first='-1')], [b[0]])['unavailableOrRenamedThreadIds'] == [1]
    try:
        thread_delta([b[0]], [a[0]])
        raise AssertionError('negative CPU delta accepted')
    except ValueError:
        pass
    try:
        snapshots([])
        raise AssertionError('missing snapshots accepted')
    except ValueError:
        pass
    print('PASS_JVM_CONTEXT_NEW_EXITED_UNAVAILABLE_NEGATIVE_AND_MISSING_CONTROLS')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--selfcheck', action='store_true')
    parser.add_argument('--evidence', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.selfcheck:
        selfcheck()
    else:
        if args.evidence is None or args.output is None:
            parser.error('--evidence and --output required')
        paths = [args.evidence / name for name in ('native-jvm-context.tsv', 'observer-free-command-cpu.tsv')]
        data = []
        for path in paths:
            with path.open() as stream:
                data.append(list(csv.DictReader(stream, delimiter='\t')))
        result = analyze(*data)
        result['inputPins'] = {str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in paths}
        with args.output.open('x') as stream:
            json.dump(result, stream, indent=2); stream.write('\n')
        print(result['status'])
