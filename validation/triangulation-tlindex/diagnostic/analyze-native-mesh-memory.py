"""Bind diagnostic table capacity/allocation and GC evidence to one JFR command clock."""
import argparse
import datetime
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess


EVENT = 'dev.turboism.validation.NativeMeshMemoryScope'
HERE = Path(__file__).resolve().parent


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec); spec.loader.exec_module(result)
    return result


def span(value):
    start = datetime.datetime.fromisoformat(value['startTime']).timestamp() * 1000
    match = re.fullmatch(r'PT(\d+(?:\.\d+)?)S', value.get('duration', 'PT0S'))
    if not match:
        raise ValueError('unsupported event duration')
    return start, start + float(Decimal(match[1]) * 1000)


def capacity(entries):
    result = 16
    while result < 2 * entries:
        result *= 2
    return result


def audit(events, windows, expected_scopes=711):
    scopes = [e for e in events if e['type'] == EVENT]
    if any(e['type'] == 'jdk.DataLoss' for e in events):
        raise ValueError('recording data loss')
    base = module('scope', HERE / 'analyze-native-mesh-scope-events.py')
    report = base.audit([dict(e, type=base.EVENT) for e in scopes], windows)
    counts = report['cycles']
    for c in counts.values():
        c.update(declaredBufferBytes=0, helperAndSuffixAllocatedBytes=0,
                 rawPrimitiveArrayBytes=0, hypotheticalInitialSizeBufferBytes=0,
                 initialEntries=0, maximumBufferBytes=0)
    for event in scopes:
        v = event['values']; start, end = span(v)
        match = [w for w in windows if w['startEpochMillis'] <= start <= end <= w['endEpochMillis']
                 and v['eventThread']['javaThreadId'] == w['javaThreadId']]
        if not match:
            continue
        for k in ('initialEntries', 'entryLimit', 'bufferCapacity', 'declaredBufferBytes',
                  'helperAndSuffixAllocatedBytes'):
            if type(v[k]) is not int or v[k] < 0:
                raise ValueError('invalid memory counter')
        if (not 0 <= v['initialEntries'] <= v['entryLimit'] <= 16384
                or v['entryLimit'] < 1 or v['bufferCapacity'] != capacity(v['entryLimit'])
                or v['declaredBufferBytes'] != 12 * v['bufferCapacity'] + 1024
                or v['helperAndSuffixAllocatedBytes'] < 12 * v['bufferCapacity']):
            raise ValueError('table capacity/allocation invariant')
        c = counts[match[0]['cycle']]
        c['declaredBufferBytes'] += v['declaredBufferBytes']
        c['rawPrimitiveArrayBytes'] += 12 * v['bufferCapacity']
        c['helperAndSuffixAllocatedBytes'] += v['helperAndSuffixAllocatedBytes']
        c['hypotheticalInitialSizeBufferBytes'] += 12 * capacity(v['initialEntries']) + 1024
        c['initialEntries'] += v['initialEntries']
        c['maximumBufferBytes'] = max(c['maximumBufferBytes'], v['declaredBufferBytes'])
    if any(c['scopes'] != expected_scopes for c in counts.values()):
        raise ValueError('complete expected successful scope count required')
    gc = []; heap = []; allocations = {}
    for event in events:
        kind, v = event['type'], event['values']
        if kind not in ('jdk.GarbageCollection', 'jdk.GCHeapSummary', 'jdk.ObjectAllocationSample'):
            continue
        start, end = span(v)
        w = next((w for w in windows if w['startEpochMillis'] <= start <= w['endEpochMillis']), None)
        phase = 'command' + str(w['cycle']) if w else ('beforeFirstCommand' if start < windows[0]['startEpochMillis']
                                                    else 'outsideCommands')
        if kind == 'jdk.GarbageCollection':
            gc.append({'phase': phase, 'startTime': v['startTime'], 'durationMillis': end - start,
                       'gcId': v['gcId'], 'name': v['name'], 'cause': v['cause']})
        elif kind == 'jdk.GCHeapSummary':
            heap.append({'phase': phase, 'startTime': v['startTime'], 'gcId': v['gcId'],
                         'when': v['when'], 'heapUsed': v['heapUsed'], 'heapSpace': v['heapSpace']})
        else:
            # Allocation weights are estimates, not exact native command allocation bytes.
            thread = v.get('eventThread') or {}
            if w and thread.get('javaThreadId') != w['javaThreadId']:
                continue
            names = [((f.get('method') or {}).get('type') or {}).get('name', '').replace('/', '.')
                     for f in (v.get('stackTrace') or {}).get('frames', [])]
            bucket = ('table' if 'dev.turboism.adapter.cubism.mesh.NativeMeshEdgeTable' in names else
                      'nativeTriangulation' if any('.triangulation.' in n for n in names) else 'other')
            slot = allocations.setdefault(phase, {}).setdefault(bucket, {'samples': 0, 'estimatedWeightBytes': 0})
            slot['samples'] += 1; slot['estimatedWeightBytes'] += v['weight']
    if not gc or not heap:
        raise ValueError('GC and heap evidence required for this independent diagnostic')
    report.update(status='PASS_DIAGNOSTIC_TABLE_MEMORY_AND_GC_BINDING', garbageCollections=gc,
                  gcHeapSummaries=heap, estimatedAllocationSamples=allocations,
                  historicalT088MemoryFailure='UNCHANGED_CAUSATION_UNPROVEN',
                  limitations=report['limitations'] + [
                      'Declared buffer bytes include a conservative 1024-byte allowance; raw arrays are 12*capacity.',
                      'Thread allocation delta includes helper setup, native suffix and diagnostic overhead; excludes gate capture/generation and first observer initialization.',
                      'Hypothetical initial-size capacity is a design estimate, not implemented or accepted.',
                      'GC classifications use the same JFR clock; no Java epoch offset or baseline marker mapping is inferred.',
                      'This new diagnostic cannot causally explain the historical failed leg or replace it.'])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording', type=Path); parser.add_argument('boundaries', type=Path)
    parser.add_argument('output', type=Path); parser.add_argument('--task-id', required=True)
    args = parser.parse_args()
    binding = module('binding', HERE / 'analyze-jfr-native-invocations.py')
    windows = binding.bind(args.recording, args.boundaries, args.task_id)
    kinds = [EVENT, 'jdk.DataLoss', 'jdk.GarbageCollection', 'jdk.GCHeapSummary', 'jdk.ObjectAllocationSample']
    result = subprocess.run(['jfr', 'print', '--json', '--events', ','.join(kinds), str(args.recording)],
                            capture_output=True, text=True, check=True)
    raw = args.output.with_suffix('.events.json')
    with raw.open('x') as stream: stream.write(result.stdout)
    report = audit(json.loads(result.stdout)['recording']['events'], windows)
    report.update(taskId=args.task_id, invocationWindows=windows, cpuPayloadFieldsMatched=30,
                  inputPins={str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in
                             (args.recording, args.boundaries, Path(__file__), raw,
                              HERE / 'analyze-native-mesh-scope-events.py', HERE / 'analyze-jfr-native-invocations.py')})
    with args.output.open('x') as stream: json.dump(report, stream, indent=2); stream.write('\n')
    print(report['status'], json.dumps(report['cycles']), flush=True)


if __name__ == '__main__':
    main()
