"""Describe CPU cycles and heap/RSS context without changing formal gate results."""
import argparse
import csv
from datetime import datetime
import hashlib
import json
from pathlib import Path
import re


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def summarize_memory(windows):
    retained = {w['operation']: w for w in windows if w['phase'] == 'retained'}
    if sorted(retained) != [1, 2, 3] or sum(w['phase'] == 'retained' for w in windows) != 3:
        raise ValueError('exactly three retained windows required')
    rows = []
    for operation, window in sorted(retained.items()):
        if len(window['boundaryHeapUsedBytes']) != 2:
            raise ValueError('two heap boundary observations required')
        host = window['roles']['host-java']
        rows.append(dict(operation=operation,
                         heapStartBytes=window['boundaryHeapUsedBytes'][0],
                         heapEndBytes=window['boundaryHeapUsedBytes'][1],
                         rssMedianBytes=host['rssMedianBytes'],
                         pssMedianBytes=host['pssMedianBytes'],
                         idleProcessCpuSeconds=host['cpuSeconds']))
    return dict(retained=rows,
                heapEnd3Minus1Bytes=rows[-1]['heapEndBytes'] - rows[0]['heapEndBytes'],
                rssMedian3Minus1Bytes=rows[-1]['rssMedianBytes'] - rows[0]['rssMedianBytes'],
                pssMedian3Minus1Bytes=rows[-1]['pssMedianBytes'] - rows[0]['pssMedianBytes'],
                idleProcessCpuSeconds=sum(row['idleProcessCpuSeconds'] for row in rows))


def compare_cycles(baseline, candidate):
    if [r['cycle'] for r in baseline] != [1, 2, 3] or [r['cycle'] for r in candidate] != [1, 2, 3]:
        raise ValueError('three ordered CPU cycles required')
    return [dict(cycle=a['cycle'], baselineCpuSeconds=a['processCpuSeconds'],
                 candidateCpuSeconds=b['processCpuSeconds'],
                 cpuDeltaSeconds=b['processCpuSeconds'] - a['processCpuSeconds'],
                 wallDeltaSeconds=b['cpuBoundaryWallSpanSeconds'] - a['cpuBoundaryWallSpanSeconds'])
            for a, b in zip(baseline, candidate)]


def parse_gc(text):
    heaps = []; pauses = []; side = None
    prefix = re.compile(r'^\[([^]]+)\]\[\d+ms\]\[[^]]+\]\[gc[^]]*\]\s+GC\((\d+)\)\s+(.*)$')
    for line in text.splitlines():
        match = prefix.match(line)
        if not match:
            continue
        timestamp, identity, message = match.groups()
        epoch = datetime.fromisoformat(timestamp).timestamp() * 1000
        if message.startswith('Heap before GC '):
            side = 'before'
        elif message.startswith('Heap after GC '):
            side = 'after'
        heap = re.search(r'garbage-first heap\s+total (\d+)K, used (\d+)K', message)
        if heap:
            if side not in ('before', 'after'):
                raise ValueError('heap row without before/after context')
            heaps.append(dict(epochMillis=epoch, gcId=int(identity), side=side,
                              committedBytes=int(heap[1]) * 1024, usedBytes=int(heap[2]) * 1024))
        pause = re.fullmatch(r'(Pause .+) (\d+(?:\.\d+)?)ms', message)
        if pause:
            pauses.append(dict(epochMillis=epoch, gcId=int(identity), kind=pause[1], durationMillis=float(pause[2])))
    if not heaps or not pauses:
        raise ValueError('G1 heap and pause observations required')
    return dict(heapObservations=heaps, pauses=pauses)


def gc_window(gc, start, end):
    prior = [h for h in gc['heapObservations'] if h['side'] == 'after' and h['epochMillis'] <= end]
    last = max(prior, key=lambda h: h['epochMillis']) if prior else None
    overlap = [dict(p, overlapMillis=max(0, min(end, p['epochMillis']) - max(start, p['epochMillis'] - p['durationMillis'])))
               for p in gc['pauses'] if p['epochMillis'] > start and p['epochMillis'] - p['durationMillis'] < end]
    return dict(lastCompletedGcHeap=last,
                lastCompletedGcAgeMillis=None if last is None else end - last['epochMillis'],
                pauseCount=len(overlap), pauseOverlapMillis=sum(p['overlapMillis'] for p in overlap),
                systemGcPauseCount=sum('System.gc()' in p['kind'] for p in overlap), pauses=overlap)


def analyze(root, work):
    report_path = work / 'balanced-review.json'
    report = json.loads(report_path.read_text())
    for path, pin in report['inputPins'].items():
        if sha(root / path) != pin:
            raise ValueError('changed formal evidence: ' + path)
    inputs = {str(report_path.relative_to(root)): sha(report_path)}
    legs = {}; cpus = {}
    for leg in report['legs']:
        name = leg['name']; directory = work / name
        resource_path = directory / 'resource-review.json'
        cpu_path = directory / 'direct-cpu-review.json'
        resource = json.loads(resource_path.read_text())
        cpu = json.loads(cpu_path.read_text())
        legs[name] = summarize_memory(resource['windows'])
        if legs[name]['rssMedian3Minus1Bytes'] != leg['retained3Minus1Bytes']:
            raise ValueError('retained RSS disagrees with formal report')
        cpus[name] = cpu['cycles']
        console = directory / 'native-evidence/console.txt'
        native = json.loads((directory / 'native-review.json').read_text())
        pins = {entry['path']: entry['sha256'] for entry in native['savedEvidence']}
        if sha(console) != pins[str(console.relative_to(root))]:
            raise ValueError('console differs from preserved native review')
        gc = parse_gc(console.read_text(errors='replace'))
        legs[name]['gcRetainedWindows'] = [dict(operation=w['operation'], **gc_window(gc, w['startEpochMillis'], w['endEpochMillis']))
                                          for w in resource['windows'] if w['phase'] == 'retained']
        boundary_path = directory / 'native-evidence/observer-free-command-cpu.tsv'
        with boundary_path.open() as stream:
            boundaries = list(csv.DictReader(stream, delimiter='\t'))
        legs[name]['gcCommandWindows'] = [dict(operation=i + 1, **gc_window(gc, int(boundaries[i * 2]['readEndEpochMillis']),
                                                                         int(boundaries[i * 2 + 1]['readStartEpochMillis'])))
                                         for i in range(3)]
        legs[name]['gcTotalPauseCount'] = len(gc['pauses'])
        legs[name]['gcTotalHeapObservationCount'] = len(gc['heapObservations'])
        for path in (console, directory / 'native-review.json', boundary_path):
            inputs[str(path.relative_to(root))] = sha(path)
        for path in (resource_path, cpu_path):
            inputs[str(path.relative_to(root))] = sha(path)
    pairs = [dict(baseline=pair['baseline'], candidate=pair['candidate'],
                  cycles=compare_cycles(cpus[pair['baseline']], cpus[pair['candidate']]))
             for pair in report['pairs']]
    return dict(status='DESCRIPTIVE_CONTEXT_ONLY_NO_CAUSAL_ATTRIBUTION',
                formalStatusUnchanged=report['status'], legs=legs, pairs=pairs,
                inputPins=inputs,
                missingEvidence=['Contemporaneous heap committed/max and memory pool residency at all boundaries',
                                 'Thread CPU attribution, including unavailable/new/exited threads; System.gc caller'],
                limitations=['Heap boundary observations are instantaneous, not retained live heap or window medians.',
                             'RSS/PSS medians and heap endpoints measure different quantities and times.',
                             'Idle process CPU may include GC, rendering, compiler and task observations; attribution is unavailable.',
                             'GC log heap is a sparse observation, not the heap at each resource marker. Pause elapsed time is not GC CPU.',
                             'No retry, gate exemption, performance acceptance or causal proof follows from this analysis.'])


def selfcheck():
    windows = [dict(phase='retained', operation=i,
                    boundaryHeapUsedBytes=[1000 + i, 1000 - i * 100],
                    roles={'host-java': dict(rssMedianBytes=i * 1000,
                                            pssMedianBytes=i * 900, cpuSeconds=i)})
               for i in (1, 2, 3)]
    memory = summarize_memory(windows)
    assert memory['heapEnd3Minus1Bytes'] == -200
    assert memory['rssMedian3Minus1Bytes'] == 2000
    assert memory['pssMedian3Minus1Bytes'] == 1800
    assert memory['idleProcessCpuSeconds'] == 6
    try:
        summarize_memory(windows[:2])
        raise AssertionError('missing window accepted')
    except ValueError:
        pass
    gc = parse_gc('[2026-10-04T03:00:00.000+0800][1ms][debug][gc,heap] GC(0) Heap after GC invocations=1 (full 0):\n'
                  '[2026-10-04T03:00:00.000+0800][1ms][debug][gc,heap] GC(0) garbage-first heap total 2048K, used 1024K\n'
                  '[2026-10-04T03:00:00.100+0800][101ms][info ][gc ] GC(0) Pause Full (System.gc()) 2M->1M(2M) 20.000ms')
    assert gc['heapObservations'][0]['committedBytes'] == 2097152
    epoch = gc['pauses'][0]['epochMillis']
    clipped = gc_window(gc, epoch - 10, epoch + 10)
    assert clipped['pauseOverlapMillis'] == 10 and clipped['systemGcPauseCount'] == 1
    assert gc_window(gc, epoch + 1, epoch + 100)['pauseCount'] == 0
    try:
        summarize_memory(windows + [windows[0]])
        raise AssertionError('duplicate window accepted')
    except ValueError:
        pass
    cycles = [dict(cycle=i, processCpuSeconds=i, cpuBoundaryWallSpanSeconds=i * 2) for i in (1, 2, 3)]
    assert all(row['cpuDeltaSeconds'] == 0 for row in compare_cycles(cycles, cycles))
    changed = [dict(row) for row in cycles]; changed[0]['processCpuSeconds'] += 4
    assert [row['cpuDeltaSeconds'] for row in compare_cycles(cycles, changed)] == [4, 0, 0]
    try:
        compare_cycles(cycles, list(reversed(cycles)))
        raise AssertionError('reordered cycles accepted')
    except ValueError:
        pass
    print('PASS_CONTEXT_ARITHMETIC_AND_INVALID_WINDOW_CYCLE_CONTROLS')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--selfcheck', action='store_true')
    parser.add_argument('--work', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.selfcheck:
        selfcheck()
    else:
        if args.work is None or args.output is None:
            parser.error('--work and --output required')
        result = analyze(Path.cwd(), args.work.resolve())
        result['analyzerSha256'] = sha(Path(__file__))
        with args.output.open('x') as stream:
            json.dump(result, stream, indent=2); stream.write('\n')
        print(result['status'])
