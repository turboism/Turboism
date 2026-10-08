"""Deterministic diagnostic-overlay scope counts inside same-recording native EDT envelopes."""
import argparse
import datetime
from decimal import Decimal
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess


EVENT = 'dev.turboism.validation.NativeMeshIndexScope'


def audit(events, windows):
    cycles = {w['cycle']: {'scopes': 0, 'tableCalls': 0, 'hits': 0, 'absent': 0,
                           'unknown': 0, 'appended': 0, 'discardedScopes': 0} for w in windows}
    outside = 0
    for event in events:
        if event['type'] != EVENT:
            raise ValueError('unexpected scope event type')
        value = event['values']
        duration = re.fullmatch(r'PT(\d+(?:\.\d+)?)S', value['duration'])
        if not duration:
            raise ValueError('invalid scope duration')
        start = datetime.datetime.fromisoformat(value['startTime']).timestamp() * 1000
        end = start + float(Decimal(duration[1]) * 1000)
        matches = [w for w in windows if w['startEpochMillis'] <= start <= end <= w['endEpochMillis']
                   and value['eventThread']['javaThreadId'] == w['javaThreadId']]
        if not matches:
            outside += 1
            continue
        if len(matches) != 1:
            raise ValueError('scope ambiguously bound')
        if value['released'] is not True or type(value['discarded']) is not bool:
            raise ValueError('scope release not proved')
        for key in ('tableCalls', 'hits', 'absent', 'unknown', 'appended'):
            if type(value[key]) is not int or value[key] < 0:
                raise ValueError('invalid scope counter')
        if value['tableCalls'] != value['hits'] + value['absent'] + value['unknown']:
            raise ValueError('table call accounting mismatch')
        if value['unknown'] != 0:
            raise ValueError('table returned UNKNOWN inside admitted scope')
        total = cycles[matches[0]['cycle']]
        total['scopes'] += 1
        total['discardedScopes'] += int(value['discarded'])
        for key in ('tableCalls', 'hits', 'absent', 'unknown', 'appended'):
            total[key] += value[key]
    if not cycles or any(c['scopes'] < 1 or c['tableCalls'] < 1 for c in cycles.values()):
        raise ValueError('every native command requires at least one actual indexed query scope')
    return {'status': 'PASS_DIAGNOSTIC_INDEXED_SCOPE_EACH_COMMAND', 'cycles': cycles,
            'outsideCommandScopeEventsExcluded': outside, 'performanceAcceptance': 'NOT_APPLICABLE_DIAGNOSTIC',
            'limitations': ['Instrumented helper overlay only; production candidate remains unchanged.',
                            'Proves indexed execution per command, not that every selected mesh is indexed.',
                            'Counters and JFR overhead prohibit performance conclusions.']}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording', type=Path)
    parser.add_argument('boundaries', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--task-id', required=True)
    args = parser.parse_args()
    source = Path(__file__).with_name('analyze-jfr-native-invocations.py')
    spec = importlib.util.spec_from_file_location('binding', source)
    binding = importlib.util.module_from_spec(spec); spec.loader.exec_module(binding)
    windows = binding.bind(args.recording, args.boundaries, args.task_id)
    raw = args.output.with_suffix('.events.json')
    result = subprocess.run(['jfr', 'print', '--json', '--events', EVENT + ',jdk.DataLoss', str(args.recording)],
                            capture_output=True, text=True, check=True)
    with raw.open('x') as stream:
        stream.write(result.stdout)
    events = json.loads(result.stdout)['recording']['events']
    if any(e['type'] == 'jdk.DataLoss' for e in events):
        raise ValueError('recording data loss; scope evidence invalid')
    report = audit(events, windows)
    report.update(taskId=args.task_id, invocationWindows=windows, cpuPayloadFieldsMatched=30,
                  inputPins={str(p): hashlib.sha256(p.read_bytes()).hexdigest()
                             for p in (args.recording, args.boundaries, source, Path(__file__), raw)})
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2); stream.write('\n')
    print(report['status'], json.dumps(report['cycles']), flush=True)


if __name__ == '__main__':
    main()
