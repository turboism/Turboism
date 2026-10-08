"""Replay frozen production samples; localize RSS changes without claiming a cause."""
import argparse
import csv
import hashlib
import json
from pathlib import Path


ORDER = ('baseline1', 'candidate1', 'candidate2', 'baseline2')


def read_tsv(path):
    with path.open() as stream:
        return list(csv.DictReader(stream, delimiter='\t'))


def analyze(work):
    work = work.resolve()
    frozen = json.loads((work / 'balanced-review.json').read_text())
    root = Path.cwd()
    for name, pin in frozen['inputPins'].items():
        if hashlib.sha256((root / name).read_bytes()).hexdigest() != pin:
            raise ValueError('frozen evidence changed: ' + name)
    report = {'status': 'PASS_FROZEN_SAMPLE_REPLAY_CAUSE_UNPROVEN', 'legs': {},
              'originalGateStatus': frozen['status'], 'productionAcceptance': 'NOT_GRANTED',
              'limitations': ['One-second RSS samples cannot identify allocation owners or exact peak times.',
                              'Heap-used boundary readings are not post-GC live size or committed heap.',
                              'A jump interval outside native timing excludes direct synchronous command timing only; concurrent host work remains possible.',
                              'No GC, heap commitment, allocation stacks or native map categories were captured.',
                              'This replay neither amends gates nor retries a native job.']}
    for name in ORDER:
        evidence = work / name / 'native-evidence'
        native = json.loads((work / name / 'native-review.json').read_text())
        for saved in native['savedEvidence']:
            if hashlib.sha256((root / saved['path']).read_bytes()).hexdigest() != saved['sha256']:
                raise ValueError('saved native evidence changed: ' + saved['path'])
        memory = json.loads((work / name / 'resource-review.json').read_text())
        cpu = read_tsv(evidence / 'observer-free-command-cpu.tsv')
        if [(r['phase'], int(r['operation'])) for r in cpu] != [
                (phase, cycle) for cycle in (1, 2, 3)
                for phase in ('native-command-before', 'native-command-after')]:
            raise ValueError('incomplete command boundaries')
        intervals = [(int(a['readEndEpochMillis']), int(b['readStartEpochMillis']))
                     for a, b in zip(cpu[::2], cpu[1::2])]
        identity = tuple(memory['javaIdentity'])
        samples = []
        for line in (evidence / 'resources.jsonl').read_text().splitlines():
            batch = json.loads(line)
            records = [r for r in batch['records'] if r['role'] == 'host-java']
            if not records:
                continue
            if len(records) != 1 or (records[0]['pid'], records[0]['startTicks']) != identity:
                raise ValueError('Java identity mismatch')
            r = records[0]
            samples.append({'epochMs': batch['epochMs'], 'rssBytes': r['rssBytes'],
                            'pssBytes': r.get('pssBytes')})
        markers = read_tsv(evidence / 'resource-windows.tsv')
        start, end = int(markers[0]['epochMillis']), int(markers[-1]['epochMillis'])
        jumps = []
        for a, b in zip(samples, samples[1:]):
            if not start <= a['epochMs'] < b['epochMs'] <= end:
                continue
            overlap = [cycle for cycle, (lo, hi) in enumerate(intervals, 1)
                       if a['epochMs'] <= hi and b['epochMs'] >= lo]
            phase = [r['phase'] for r in markers
                     if a['epochMs'] <= int(r['epochMillis']) <= b['epochMs']]
            jumps.append({'startEpochMillis': a['epochMs'], 'endEpochMillis': b['epochMs'],
                          'rssChangeBytes': b['rssBytes'] - a['rssBytes'],
                          'pssChangeBytes': None if a['pssBytes'] is None or b['pssBytes'] is None
                          else b['pssBytes'] - a['pssBytes'],
                          'nativeCommandOverlapCycles': overlap, 'crossedMarkers': phase})
        if not jumps:
            raise ValueError('no bounded sample transitions')
        retained = [w for w in memory['windows'] if w['phase'] == 'retained']
        if len(retained) != 3:
            raise ValueError('incomplete retained windows')
        heap = [w['boundaryHeapUsedBytes'][-1] for w in retained]
        report['legs'][name] = {
            'largestRssRise': max(jumps, key=lambda x: x['rssChangeBytes']),
            'largestRssFall': min(jumps, key=lambda x: x['rssChangeBytes']),
            'retainedEndHeapUsedBytes': heap,
            'retainedEndHeap3Minus1Bytes': heap[-1] - heap[0],
            'retainedMedianRss3Minus1Bytes': memory['lastMinusFirstRetainedJavaMedianRssBytes'],
            'sampleTransitionCount': len(jumps),
        }
    report['inputPins'] = dict(frozen['inputPins'])
    report['inputPins'][str((work / 'balanced-review.json').relative_to(root))] = hashlib.sha256(
        (work / 'balanced-review.json').read_bytes()).hexdigest()
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('work', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    report = analyze(args.work)
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')
    print(report['status'])
    for name, leg in report['legs'].items():
        jump = leg['largestRssRise']
        print(name, 'largest RSS rise MiB', round(jump['rssChangeBytes'] / 1048576, 3),
              'native overlap', jump['nativeCommandOverlapCycles'],
              'retained heap3-1 MiB', round(leg['retainedEndHeap3Minus1Bytes'] / 1048576, 3))


if __name__ == '__main__':
    main()
