"""Classify repeated target execution for the undo diagnostic, never performance acceptance."""
import argparse
import csv
import datetime
import importlib.util
import json
from pathlib import Path

spec = importlib.util.spec_from_file_location('resource_windows', Path(__file__).resolve().parents[1] / 'analyze-resource-windows.py')
windows = importlib.util.module_from_spec(spec)
spec.loader.exec_module(windows)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def analyze(markers, undo, jfr, outcome):
    result = json.loads(outcome.read_text())
    require(result.get('validationStatus') == 'PASS' and result.get('cleanup') == 'safe', 'host gates failed')
    require(all(result.get(k) is True for k in ('normalExit', 'identityVerified', 'fixtureUnchanged')), 'host identity/exit/fixture failed')
    with markers.open() as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    expected = [('baseline-start', 0), ('baseline-end', 0)]
    for n in range(1, 4):
        expected.extend((phase, n) for phase in ('operation-start', 'operation-end', 'diagnostic-undo-start', 'diagnostic-undo-end', 'retained-start', 'retained-end'))
    require(len(rows) == len(expected), 'incomplete diagnostic markers')
    for row, (phase, operation) in zip(rows, expected):
        require(row['phase'] == phase and int(row['operation']) == operation, 'unexpected diagnostic phase')
        for key in ('epochMillis', 'monotonicNanos'):
            row[key] = int(row[key])
    for a, b in zip(rows, rows[1:]):
        require(b['monotonicNanos'] > a['monotonicNanos'] and b['epochMillis'] >= a['epochMillis'], 'unordered markers')
    undo_rows = [line.split('\t') for line in undo.read_text().splitlines()]
    require(len(undo_rows) == 3 and all(len(r) == 2 for r in undo_rows), 'incomplete undo receipts')
    require([int(r[0]) for r in undo_rows] == [1, 2, 3], 'unexpected undo sequence')
    positions = [int(r[1]) for r in undo_rows]
    require(min(positions) >= 0 and len(set(positions)) == 1, 'undo baseline position drift')
    operations = []
    for n in range(1, 4):
        start = next(r for r in rows if r['phase'] == 'operation-start' and int(r['operation']) == n)
        end = next(r for r in rows if r['phase'] == 'operation-end' and int(r['operation']) == n)
        operations.append({'operation': n, 'startEpochMillis': start['epochMillis'], 'endEpochMillis': end['epochMillis'], 'nativeTriangulationSamples': 0, 'productionIndexSamples': 0})
    for event in windows.jfr_events(jfr):
        if event['type'] not in ('jdk.ExecutionSample', 'jdk.NativeMethodSample'):
            continue
        value = event['values']
        stamp = datetime.datetime.fromisoformat(value['startTime']).timestamp() * 1000
        names = [frame['method']['type']['name'].replace('/', '.') for frame in (value.get('stackTrace') or {}).get('frames', [])]
        for operation in operations:
            if operation['startEpochMillis'] <= stamp < operation['endEpochMillis']:
                operation['nativeTriangulationSamples'] += int(any(n.startswith('com.live2d.graphics3d.editableMesh.triangulation.') for n in names))
                operation['productionIndexSamples'] += int(any(n == 'dev.turboism.adapter.cubism.mesh.TriangulationEdgeIndex' for n in names))
    observed = all(o['nativeTriangulationSamples'] > 0 for o in operations)
    return {'schemaVersion': 1, 'triggerVerdict': 'REPEATED_TARGET_OBSERVED' if observed else 'REPEATED_TARGET_UNPROVEN',
            'performanceAcceptance': 'NOT_APPLICABLE_DIAGNOSTIC', 'perCycleOutputEquivalence': 'NOT_PROVEN',
            'scope': 'Sampling proves observed execution only; no invocation counts, output equality or retention verdict.',
            'inputs': {str(p): windows.file_sha256(p) for p in (markers, undo, jfr, outcome)},
            'restoredPosition': positions[0], 'operations': operations}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('markers', 'undo', 'jfr', 'outcome', 'output'):
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    report = analyze(args.markers, args.undo, args.jfr, args.outcome)
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')
    print(report['triggerVerdict'])
