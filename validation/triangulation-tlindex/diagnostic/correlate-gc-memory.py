"""Correlate distinct GC diagnostic logs and samples, without performance gates."""
import argparse
import csv
import io
import hashlib
import importlib.util
import json
from pathlib import Path


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('work', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    root = Path.cwd()
    work = args.work.resolve()
    protocol_path = root / 'validation/triangulation-tlindex/production-gc-explanation-protocol-20261003.json'
    if sha(protocol_path) != (work / 'protocol.sha256').read_text().strip():
        raise ValueError('changed frozen diagnostic protocol')
    protocol = json.loads(protocol_path.read_text())
    for name, pin in protocol['inputPins'].items():
        if sha(root / name) != pin:
            raise ValueError('changed frozen diagnostic input: ' + name)
    path = root / 'validation/triangulation-tlindex/analyze-resource-windows.py'
    spec = importlib.util.spec_from_file_location('resource', path)
    resource = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(resource)
    report = {'status': 'PASS_BOUND_GC_MEMORY_DIAGNOSTIC_CAUSE_UNPROVEN', 'legs': {},
              'performanceAcceptance': 'NOT_APPLICABLE_DIAGNOSTIC_LOGGING',
              'inputPins': {str(protocol_path.relative_to(root)): sha(protocol_path)},
              'limitations': ['Two new diagnostic jobs cannot explain an earlier process without its GC logs.',
                              'Timestamp proximity is not allocation ownership or causal proof.',
                              'RSS samples and GC used/committed heap are distinct quantities.',
                              'Original failed production comparison is unchanged; no replacement performance legs.']}
    mapping = {'mesh-baseline-start': 'baseline-start', 'mesh-baseline-end': 'baseline-end',
               'command-dispatch-start': 'operation-start', 'command-observation-end': 'operation-end',
               'mesh-retained-start': 'retained-start', 'mesh-retained-end': 'retained-end'}
    for name in ('baseline', 'candidate'):
        out = work / name
        evidence = out / 'native-evidence'
        native = json.loads((out / 'native-review.json').read_text())
        run = json.loads((out / 'run.json').read_text())
        if run['state'] != 'succeeded' or native['referenceMismatchCount'] != 0:
            raise ValueError('invalid native diagnostic result')
        for saved in native['savedEvidence']:
            if sha(root / saved['path']) != saved['sha256']:
                raise ValueError('changed native evidence')
        gc = json.loads((out / 'gc-review.json').read_text())
        if gc['inputSha256'] != sha(evidence / 'console.txt'):
            raise ValueError('GC console mismatch')
        with (evidence / 'resource-windows.tsv').open() as stream:
            markers = list(csv.DictReader(stream, delimiter='\t'))
        mapped = out / 'mapped-resource-windows.tsv'
        buffer = io.StringIO(newline='')
        writer = csv.DictWriter(buffer, fieldnames=list(markers[0]), delimiter='\t')
        writer.writeheader()
        writer.writerows(dict(r, phase=mapping[r['phase']]) for r in markers)
        expected = buffer.getvalue().encode()
        if mapped.exists():
            if mapped.read_bytes() != expected:
                raise ValueError('existing phase projection differs')
        else:
            with mapped.open('xb') as stream:
                stream.write(expected)
        memory = resource.analyze(mapped, evidence / 'resources.jsonl')
        memory_path = out / 'resource-review.json'
        if memory_path.exists():
            if json.loads(memory_path.read_text()) != memory:
                raise ValueError('existing resource analysis differs')
        else:
            memory_path.write_text(json.dumps(memory, indent=2) + '\n')
        lo, hi = int(markers[0]['epochMillis']), int(markers[-1]['epochMillis'])
        active = [s for s in gc['heapSummaries'] if lo <= s['epochMillis'] <= hi]
        if not active:
            raise ValueError('no GC heap summary in task resource windows')
        committed_changes = [dict(previousObservedEpochMillis=a['epochMillis'],
                                 firstLargerOrSmallerObservedEpochMillis=b['epochMillis'], gcId=b['gcId'],
                                 changeBytes=b['committedHeapBytes'] - a['committedHeapBytes'])
                             for a, b in zip(active, active[1:])
                             if a['committedHeapBytes'] != b['committedHeapBytes']]
        samples = [json.loads(line) for line in (evidence / 'resources.jsonl').read_text().splitlines()]
        points = [(s['epochMs'], next(r['rssBytes'] for r in s['records'] if r['role'] == 'host-java'))
                  for s in samples if lo <= s['epochMs'] <= hi
                  and any(r['role'] == 'host-java' for r in s['records'])]
        largest = max(({'startEpochMillis': a[0], 'endEpochMillis': b[0], 'rssChangeBytes': b[1] - a[1]}
                       for a, b in zip(points, points[1:])), key=lambda x: x['rssChangeBytes'])
        largest['nearbyGcHeapSummaries'] = [s for s in active if
            largest['startEpochMillis'] - 1000 <= s['epochMillis'] <= largest['endEpochMillis'] + 1000]
        largest['withinCommitmentObservationBrackets'] = [c for c in committed_changes if
            c['previousObservedEpochMillis'] <= largest['startEpochMillis']
            and largest['endEpochMillis'] <= c['firstLargerOrSmallerObservedEpochMillis']]
        operations = [w for w in memory['windows'] if w['phase'] == 'operation']
        peak = max(w['roles']['host-java']['rssPeakBytes'] for w in operations)
        baseline = memory['windows'][0]['roles']['host-java']['rssMedianBytes']
        report['legs'][name] = {'sequence': native['sequence'], 'jobId': native['jobId'],
            'resourceWindowGcSummaryCount': len(active), 'committedHeapChanges': committed_changes,
            'committedHeapFirstBytes': active[0]['committedHeapBytes'],
            'committedHeapLastBytes': active[-1]['committedHeapBytes'],
            'largestSampledRssRise': largest, 'operationPeakRssBytes': peak,
            'peakAboveOwnBaselineRssBytes': peak - baseline,
            'retainedMedianRss3Minus1Bytes': memory['lastMinusFirstRetainedJavaMedianRssBytes']}
        for p in (out / 'run.json', out / 'native-review.json', out / 'gc-review.json',
                  out / 'resource-review.json', evidence / 'console.txt', evidence / 'resources.jsonl'):
            report['inputPins'][str(p.relative_to(root))] = sha(p)
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')
    print(report['status'])
    for name, leg in report['legs'].items():
        print(name, 'largest RSS rise MiB', leg['largestSampledRssRise']['rssChangeBytes'] / 1048576,
              'retained RSS3-1 MiB', leg['retainedMedianRss3Minus1Bytes'] / 1048576,
              'commitment changes', leg['committedHeapChanges'])


if __name__ == '__main__':
    main()
