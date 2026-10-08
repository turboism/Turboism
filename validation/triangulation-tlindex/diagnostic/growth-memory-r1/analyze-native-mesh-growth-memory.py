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


EVENT = 'dev.turboism.validation.NativeMeshGrowthMemoryScope'
HERE = Path(__file__).resolve().parent
BASE = HERE.parent


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


def validate(v):
    keys = ('initialEntries', 'entryLimit', 'initialBufferCapacity', 'finalBufferCapacity', 'growthCount',
            'initialDeclaredBufferBytes', 'finalDeclaredBufferBytes', 'peakLiveDeclaredBufferBytes',
            'cumulativeDeclaredBufferBytes', 'rawPrimitiveArrayAllocatedBytes', 'helperAndSuffixAllocatedBytes')
    if any(type(v.get(k)) is not int or v[k] < 0 for k in keys):
        raise ValueError('invalid growth memory counter')
    if not 0 <= v['initialEntries'] <= v['entryLimit'] <= 16384 or v['entryLimit'] < 1:
        raise ValueError('invalid growth entry bounds')
    initial = capacity(v['initialEntries'])
    growth = v['growthCount']
    if growth > 11:
        raise ValueError('growth exceeds admitted capacity')
    capacities = [initial * 2 ** i for i in range(growth + 1)]
    final = capacities[-1]
    expected_peak = 12 * initial + 1024 if growth == 0 else 12 * (final + final // 2) + 2048
    if (v['initialBufferCapacity'] != initial or v['finalBufferCapacity'] != final
            or final > capacity(v['entryLimit'])
            or v['initialDeclaredBufferBytes'] != 12 * initial + 1024
            or v['finalDeclaredBufferBytes'] != 12 * final + 1024
            or v['peakLiveDeclaredBufferBytes'] != expected_peak or expected_peak > 4 * 1024 * 1024
            or v['cumulativeDeclaredBufferBytes'] != sum(12 * c + 1024 for c in capacities)
            or v['rawPrimitiveArrayAllocatedBytes'] != sum(12 * c for c in capacities)
            or v['helperAndSuffixAllocatedBytes'] < v['rawPrimitiveArrayAllocatedBytes']):
        raise ValueError('growth capacity/allocation invariant')


def audit(events, windows, expected_scopes=711):
    scopes = [e for e in events if e['type'] == EVENT]
    if any(e['type'] == 'jdk.DataLoss' for e in events):
        raise ValueError('recording data loss')
    base = module('scope', BASE / 'analyze-native-mesh-scope-events.py')
    report = base.audit([dict(e, type=base.EVENT) for e in scopes], windows)
    counts = report['cycles']
    for c in counts.values():
        c.update(initialDeclaredBufferBytes=0, finalDeclaredBufferBytes=0, helperAndSuffixAllocatedBytes=0,
                 rawPrimitiveArrayAllocatedBytes=0, cumulativeDeclaredBufferBytes=0,
                 growthCount=0, initialEntries=0, maximumLiveDeclaredBufferBytes=0)
    for event in scopes:
        v = event['values']; start, end = span(v)
        match = [w for w in windows if w['startEpochMillis'] <= start <= end <= w['endEpochMillis']
                 and v['eventThread']['javaThreadId'] == w['javaThreadId']]
        if not match:
            continue
        validate(v)
        c = counts[match[0]['cycle']]
        for key in ('initialDeclaredBufferBytes', 'finalDeclaredBufferBytes', 'helperAndSuffixAllocatedBytes',
                    'rawPrimitiveArrayAllocatedBytes', 'cumulativeDeclaredBufferBytes', 'growthCount', 'initialEntries'):
            c[key] += v[key]
        c['maximumLiveDeclaredBufferBytes'] = max(c['maximumLiveDeclaredBufferBytes'], v['peakLiveDeclaredBufferBytes'])
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
    report.update(status='PASS_DIAGNOSTIC_GROWTH_MEMORY_AND_GC_BINDING', garbageCollections=gc,
                  gcHeapSummaries=heap, estimatedAllocationSamples=allocations,
                  historicalT088MemoryFailure='UNCHANGED_CAUSATION_UNPROVEN',
                  limitations=report['limitations'] + [
                      'Declared buffer bytes include a conservative 1024-byte allowance; raw arrays are 12*capacity.',
                      'Thread allocation delta includes helper setup, native suffix and diagnostic overhead; excludes gate capture/generation and first observer initialization.',
                      'Initial, final, cumulative and peak old-plus-replacement declared buffer counters observe the implemented growth path; they are not heap or RSS measurements.',
                      'GC classifications use the same JFR clock; no Java epoch offset or baseline marker mapping is inferred.',
                      'This new diagnostic cannot causally explain the historical failed leg or replace it.'])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording', type=Path); parser.add_argument('boundaries', type=Path)
    parser.add_argument('output', type=Path); parser.add_argument('--task-id', required=True)
    args = parser.parse_args()
    binding = module('binding', BASE / 'analyze-jfr-native-invocations.py')
    windows = binding.bind(args.recording, args.boundaries, args.task_id)
    kinds = [EVENT, 'jdk.DataLoss', 'jdk.GarbageCollection', 'jdk.GCHeapSummary', 'jdk.ObjectAllocationSample']
    result = subprocess.run(['jfr', 'print', '--json', '--stack-depth', '64', '--events', ','.join(kinds), str(args.recording)],
                            capture_output=True, text=True, check=True)
    raw = args.output.with_suffix('.events.json')
    with raw.open('x') as stream: stream.write(result.stdout)
    report = audit(json.loads(result.stdout)['recording']['events'], windows)
    report.update(taskId=args.task_id, invocationWindows=windows, cpuPayloadFieldsMatched=30,
                  inputPins={str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in
                             (args.recording, args.boundaries, Path(__file__), raw,
                              BASE / 'analyze-native-mesh-scope-events.py', BASE / 'analyze-jfr-native-invocations.py')})
    with args.output.open('x') as stream: json.dump(report, stream, indent=2); stream.write('\n')
    print(report['status'], json.dumps(report['cycles']), flush=True)


if __name__ == '__main__':
    main()
