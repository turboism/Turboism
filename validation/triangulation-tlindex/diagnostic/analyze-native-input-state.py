"""Join six detached input snapshots with command-return cache states; no causal/performance claim."""
import argparse
import base64
import csv
import hashlib
import json
from pathlib import Path
import re


def analyze(inputs, results, stacks, task):
    if stacks['taskId'] != task or stacks['cpuPayloadFieldsMatched'] != 30:
        raise ValueError('same-JFR command binding required')
    if [(r['cycle'], r['phase']) for r in inputs] != [(str(c), p) for c in (1, 2, 3) for p in ('before', 'after')]:
        raise ValueError('six ordered input states required')
    decoded = []
    for row in inputs:
        if set(row) != {'cycle', 'phase', 'widgetBase64', 'subtoolBase64', 'viewsBase64'}:
            raise ValueError('invalid input schema')
        values = {}
        for key in ('widgetBase64', 'subtoolBase64', 'viewsBase64'):
            value = base64.b64decode(row[key], validate=True).decode('utf-8')
            if not value or base64.b64encode(value.encode()).decode() != row[key]:
                raise ValueError('noncanonical or empty input encoding')
            values[key.removesuffix('Base64')] = value
        if not re.fullmatch(r'null|com\.live2d\.[\w.$]+@\d+', values['widget']):
            raise ValueError('invalid widget identity shape')
        decoded.append(values)
    if len(results) != 2133: raise ValueError('complete command-return scope required')
    cycles = []
    selected = None
    for cycle in (1, 2, 3):
        rows = [r for r in results if r['cycle'] == str(cycle)]
        ids = [r['sourceIdBase64'] for r in rows]
        if len(rows) != 711 or len(set(ids)) != 711 or (selected is not None and ids != selected):
            raise ValueError('ordered source identity changed')
        selected = ids
        for row in rows:
            if (int(row['pointCount']) < 3 or int(row['positionValues']) != 2 * int(row['pointCount'])
                    or int(row['indexValues']) <= 0 or int(row['indexValues']) % 3
                    or row['positionVersion'] != row['vertexCacheVersion']
                    or row['beforeEdgeVersion'] == row['commandEdgeVersion']):
                raise ValueError('invalid command output shape/version')
        before, after = decoded[2 * cycle - 2:2 * cycle]
        stack = stacks['cycles'].get(str(cycle)) or stacks['cycles'].get(cycle)
        if stack is None: raise ValueError('missing stack cycle')
        cycles.append({'cycle': cycle, 'before': before, 'after': after,
                       'widgetTextUnchanged': before['widget'] == after['widget'],
                       'subtoolTextUnchanged': before['subtool'] == after['subtool'],
                       'invalidatedIndexCacheSources': sum(r['indexCacheVersion'] == '-1' for r in rows),
                       'currentIndexCacheSources': sum(r['indexCacheVersion'] == r['commandEdgeVersion'] for r in rows),
                       'fullChainExecutionSamples': stack['fullChainExecutionSamples'],
                       'fullChainAllocationSamples': stack['fullChainAllocationSamples']})
    return {'status': 'PASS_INPUT_CACHE_STACK_JOIN_DIAGNOSTIC_ONLY', 'taskId': task, 'cycles': cycles,
            'causation': 'NOT_PROVEN', 'performanceAcceptance': 'NOT_GRANTED',
            'limitations': ['Identity hashes are display metadata, not proof of object uniqueness.',
                           'Before/after snapshots do not sample every intermediate input state.',
                           'No sampled chain does not prove no execution; state correlation does not prove historical cause.']}


def sha(path):
    with path.open('rb') as stream: return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('inputs', type=Path); parser.add_argument('results', type=Path)
    parser.add_argument('stacks', type=Path); parser.add_argument('output', type=Path)
    parser.add_argument('--task-id', required=True); args = parser.parse_args()
    paths = [args.inputs, args.results, args.stacks, Path(__file__)]
    pins = {str(p): sha(p) for p in paths}
    def read(path):
        with path.open() as stream: return list(csv.DictReader(stream, delimiter='\t'))
    report = analyze(read(args.inputs), read(args.results), json.loads(args.stacks.read_text()), args.task_id)
    if any(sha(Path(p)) != pin for p, pin in pins.items()): raise ValueError('analysis input changed')
    report['inputPins'] = pins
    with args.output.open('x') as stream: json.dump(report, stream, indent=2); stream.write('\n')
    print(report['status'])


if __name__ == '__main__': main()
