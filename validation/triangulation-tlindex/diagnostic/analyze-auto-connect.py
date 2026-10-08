"""Verify native auto-connect diagnostic evidence; never grants atlas performance acceptance."""
import argparse
import base64
import csv
import datetime
import importlib.util
import json
import re
from pathlib import Path

spec = importlib.util.spec_from_file_location('windows', Path(__file__).resolve().parents[1] / 'analyze-resource-windows.py')
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def analyze(markers, selected, results, protocol, jfr, outcome):
    terminal = json.loads(outcome.read_text())
    require(terminal.get('validationStatus') == 'PASS' and terminal.get('cleanup') == 'safe', 'host gates failed')
    require(all(terminal.get(k) is True for k in ('normalExit', 'identityVerified', 'fixtureUnchanged')), 'host identity/exit/fixture failed')
    require(protocol.read_text() == 'scope=DIAGNOSTIC_ONLY\nrebuild=true\npreserveBorder=true\ncycles=3\n', 'unexpected native options')
    ids = selected.read_text().splitlines()
    require(ids and len(ids) == len(set(ids)), 'empty or duplicate selected IDs')
    for value in ids:
        decoded = base64.b64decode(value, validate=True)
        require(decoded and base64.b64encode(decoded).decode() == value, 'invalid encoded source ID')
        decoded.decode('utf-8')
    with markers.open() as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    expected = [('mesh-enter-start', 0), ('mesh-enter-end', 0), ('mesh-baseline-start', 0), ('mesh-baseline-end', 0)]
    for n in range(1, 4):
        expected.extend((phase, n) for phase in ('auto-connect-start', 'auto-connect-returned', 'auto-connect-end', 'mesh-retained-start', 'mesh-retained-end'))
    expected.extend((phase, 0) for phase in ('mesh-cancel-start', 'mesh-cancel-end'))
    require(len(rows) == len(expected), 'incomplete diagnostic markers')
    for row, (phase, n) in zip(rows, expected):
        require(row['phase'] == phase and int(row['operation']) == n, 'unexpected diagnostic phase')
        for key in ('epochMillis', 'monotonicNanos'):
            row[key] = int(row[key])
    require(all(b['monotonicNanos'] > a['monotonicNanos'] and b['epochMillis'] >= a['epochMillis']
                for a, b in zip(rows, rows[1:])), 'unordered markers')
    cycles = []
    for n in range(1, 4):
        marks = {r['phase']: r for r in rows if int(r['operation']) == n}
        cycles.append({'cycle': n, 'startEpochMillis': marks['auto-connect-start']['epochMillis'],
                       'returnedEpochMillis': marks['auto-connect-returned']['epochMillis'],
                       'endEpochMillis': marks['auto-connect-end']['epochMillis'],
                       'commandTargetSamples': 0, 'settlingTargetSamples': 0, 'unknownFrames': 0,
                       'results': []})
    for line in results.read_text().splitlines():
        fields = line.split('\t')
        require(len(fields) == 8, 'invalid result columns')
        n, source, points, edge_version, positions, indices, psha, isha = fields
        n, points, edge_version, positions, indices = map(int, (n, points, edge_version, positions, indices))
        require(1 <= n <= 3 and points >= 3 and positions == points * 2 and indices > 0 and indices % 3 == 0, 'invalid result shape')
        require(all(re.fullmatch('[a-f0-9]{64}', value) for value in (psha, isha)), 'invalid result digest')
        cycles[n - 1]['results'].append({'sourceIdBase64': source, 'pointCount': points,
            'edgeVersion': edge_version, 'positionValues': positions, 'indexValues': indices,
            'positionsSha256': psha, 'indicesSha256': isha})
    for cycle in cycles:
        require([r['sourceIdBase64'] for r in cycle['results']] == ids, 'missing/reordered/duplicate cycle result')
    for event in w.jfr_events(jfr):
        if event['type'] not in ('jdk.ExecutionSample', 'jdk.NativeMethodSample'):
            continue
        value = event['values']
        stamp = datetime.datetime.fromisoformat(value['startTime']).timestamp() * 1000
        frames = (value.get('stackTrace') or {}).get('frames', [])
        names = [((f.get('method') or {}).get('type') or {}).get('name') for f in frames]
        target = any(isinstance(name, str) and name.replace('/', '.').startswith(
            'com.live2d.graphics3d.editableMesh.triangulation.') for name in names)
        unknown = sum(not isinstance(name, str) for name in names)
        for cycle in cycles:
            if cycle['startEpochMillis'] <= stamp < cycle['endEpochMillis']:
                key = 'commandTargetSamples' if stamp < cycle['returnedEpochMillis'] else 'settlingTargetSamples'
                cycle[key] += int(target)
                cycle['unknownFrames'] += unknown
    # Require observations inside every actual command, not just delayed repaint.
    observed = all(c['commandTargetSamples'] > 0 for c in cycles)
    return {'schemaVersion': 1, 'triggerVerdict': 'REPEATED_COMMAND_TARGET_OBSERVED' if observed else 'REPEATED_TARGET_UNPROVEN',
            'performanceAcceptance': 'NOT_APPLICABLE_DIAGNOSTIC', 'crossRunOutputEquivalence': 'NOT_ASSESSED',
            'scope': 'Observed samples only; no invocation counts, memory safety or atlas-workload acceptance.',
            'cycles': cycles, 'inputs': {str(p): w.file_sha256(p) for p in (markers, selected, results, protocol, jfr, outcome)}}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('markers', 'selected', 'results', 'protocol', 'jfr', 'outcome', 'output'):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    result = analyze(args.markers, args.selected, args.results, args.protocol, args.jfr, args.outcome)
    with args.output.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(result['triggerVerdict'])
