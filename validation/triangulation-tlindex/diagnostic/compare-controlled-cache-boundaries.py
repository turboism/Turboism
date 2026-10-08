"""Compare full controlled command output/cache transitions; never interprets timing as performance."""
import argparse
import csv
import hashlib
import json
from pathlib import Path

FIELDS = ('cycle', 'sourceIdBase64', 'pointCount', 'positionValues', 'indexValues',
          'positionsSha256', 'indicesSha256', 'positionVersion', 'vertexCacheVersion')


def transition(row):
    return ((int(row['commandEdgeVersion']) - int(row['beforeEdgeVersion'])) & 0xffffffff,
            row['indexCacheVersion'] == row['commandEdgeVersion'], row['indexCacheVersion'] == '-1')


def compare(before, after):
    if len(before) != 2133 or len(after) != 2133:
        raise ValueError('two complete 2133-row command scopes required')
    mismatches = []
    for cycle in (1, 2, 3):
        a = [r for r in before if r['cycle'] == str(cycle)]
        b = [r for r in after if r['cycle'] == str(cycle)]
        if len(a) != 711 or len(b) != 711: raise ValueError('711 sources each cycle required')
        if len({r['sourceIdBase64'] for r in a}) != 711 or len({r['sourceIdBase64'] for r in b}) != 711:
            raise ValueError('unique sources required')
        for old, new in zip(a, b):
            changed = [k for k in FIELDS if old[k] != new[k]]
            if transition(old) != transition(new): changed.append('edgeDeltaAndCacheValidity')
            if not transition(old)[1] or not transition(new)[1]: changed.append('controlledCacheNotCurrent')
            if changed: mismatches.append({'cycle': cycle, 'sourceIdBase64': old['sourceIdBase64'], 'fields': changed})
    return {'status': 'FAIL_CONTROLLED_COMMAND_BOUNDARY_DIFFERENTIAL' if mismatches
            else 'PASS_CONTROLLED_COMMAND_BOUNDARY_DIFFERENTIAL_ONLY',
            'rows': 2133, 'mismatchCount': len(mismatches), 'mismatches': mismatches,
            'performanceAcceptance': 'NOT_GRANTED', 'historicalFailures': 'UNCHANGED',
            'versionScope': 'Compare per-command uint32 edge delta/cache validity, exact point/cache-vertex versions; absolute edge counters are process-local.'}


def sha(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path); parser.add_argument('candidate', type=Path)
    parser.add_argument('output', type=Path); args = parser.parse_args()
    paths = [Path(__file__)]
    reports = []
    for leg in (args.baseline, args.candidate):
        for name in ('native-review.json', 'cache-refresh-review.json', 'input-cache-stack-review.json'):
            paths.append(leg / name)
        native = json.loads((leg / 'native-review.json').read_text())
        stack = json.loads((leg / 'cache-refresh-review.json').read_text())
        joined = json.loads((leg / 'input-cache-stack-review.json').read_text())
        if (native['standardGates'] != 'PASS' or stack['taskId'] != native['runId']
                or joined['taskId'] != native['runId'] or stack['cpuPayloadFieldsMatched'] != 30
                or not stack['fullChainObservedEachCycle']):
            raise ValueError('validated task/EDT/sampled refresh chain required for both legs')
        if any(c['currentIndexCacheSources'] != 711 or c['invalidatedIndexCacheSources'] != 0
               or c['before']['widget'] == 'null' or c['after']['widget'] == 'null'
               or c['before']['subtool'] != 'POINT_ADD' or c['after']['subtool'] != 'POINT_ADD'
               for c in joined['cycles']):
            raise ValueError('same controlled input/cache boundary required')
        path = leg / 'native-evidence/observer-free-results.tsv'; paths.append(path)
        with path.open() as stream: reports.append(list(csv.DictReader(stream, delimiter='\t')))
    pins = {str(p): sha(p) for p in paths}
    report = compare(*reports)
    report['inputPins'] = pins
    if any(sha(Path(p)) != pin for p, pin in pins.items()): raise ValueError('comparison input changed')
    with args.output.open('x') as stream: json.dump(report, stream, indent=2); stream.write('\n')
    print(report['status'], 'mismatches=' + str(report['mismatchCount']))


if __name__ == '__main__': main()
