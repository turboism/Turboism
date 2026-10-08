"""Bind command envelopes and samples on one JFR clock, independently of Java epoch."""
import argparse
import csv
import datetime
from decimal import Decimal
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import subprocess


EVENT = 'dev.turboism.validation.NativeCommandInvocation'


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def bind(recording, boundaries, task):
    result = subprocess.run(['jfr', 'print', '--json', '--events', EVENT, str(recording)],
                            capture_output=True, text=True, check=True)
    events = json.loads(result.stdout)['recording']['events']
    with boundaries.open() as stream:
        rows = list(csv.DictReader(stream, delimiter='\t'))
    if [(r['phase'], int(r['operation'])) for r in rows] != [
            (phase, cycle) for cycle in (1, 2, 3)
            for phase in ('native-command-before', 'native-command-after')]:
        raise ValueError('six ordered CPU reads required')
    if len(events) != 3 or [e['values']['cycle'] for e in events] != [1, 2, 3]:
        raise ValueError('three ordered unique invocation events required')
    windows = []
    fields = {'CpuNanos': 'processCpuNanos', 'ReadStartNanos': 'readStartNanos',
              'ReadEndNanos': 'readEndNanos', 'ReadStartEpochMillis': 'readStartEpochMillis',
              'ReadEndEpochMillis': 'readEndEpochMillis'}
    for i, event in enumerate(events):
        value = event['values']
        if event['type'] != EVENT or value['taskId'] != task or value['nativeReturned'] is not True:
            raise ValueError('event task/type/return mismatch')
        for prefix, row in zip(('before', 'after'), rows[2*i:2*i+2]):
            for field, column in fields.items():
                if value[prefix + field] != int(row[column]):
                    raise ValueError('event/CPU counter or clock payload mismatch')
        thread = value['eventThread']
        if not thread['javaName'].startswith('AWT-EventQueue'):
            raise ValueError('event is not on native command EDT')
        duration = re.fullmatch(r'PT(\d+(?:\.\d+)?)S', value['duration'])
        if not duration or Decimal(duration[1]) <= 0:
            raise ValueError('invalid invocation event duration')
        start = datetime.datetime.fromisoformat(value['startTime']).timestamp() * 1000
        end = start + float(Decimal(duration[1]) * 1000)
        if windows and start <= windows[-1]['endEpochMillis']:
            raise ValueError('overlapping invocation envelopes')
        windows.append({'cycle': i + 1, 'startEpochMillis': start, 'endEpochMillis': end,
                        'javaThreadId': thread['javaThreadId'], 'javaThreadName': thread['javaName'],
                        'durationSeconds': str(Decimal(duration[1]))})
    if len({w['javaThreadId'] for w in windows}) != 1:
        raise ValueError('native command EDT identity changed')
    return windows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording', type=Path)
    parser.add_argument('boundaries', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--task-id', required=True)
    parser.add_argument('--bind-only', action='store_true')
    args = parser.parse_args()
    diag = Path(__file__).resolve().parent
    pins = {str(p): sha(p) for p in (args.recording, args.boundaries, Path(__file__))}
    windows = bind(args.recording, args.boundaries, args.task_id)
    report = {'status': 'PASS_SAME_RECORDING_INVOCATION_BINDING_ONLY', 'taskId': args.task_id,
              'invocationWindows': windows, 'cpuPayloadFieldsMatched': 30,
              'performanceAcceptance': 'NOT_GRANTED', 'inputPins': pins,
              'limitations': ['Invocation envelopes include the two CPU reads and command dispatch, exclude preparation/output snapshots.',
                              'Sampled weights and execution-stack counts are not exact allocation bytes or CPU time.',
                              'JFR and Java epoch need not agree: the ten payload fields per event bind independent text evidence.',
                              'This is a distinct diagnostic; historical clock-unproven ownership and failed performance gates remain unchanged.']}
    if not args.bind_only:
        old_path = diag / 'analyze-production-ownership.py'
        reader_path = diag.parent / 'analyze-resource-windows.py'
        old = module('ownership', old_path)
        reader = module('resource', reader_path)
        projection = args.output.with_suffix('.jfr-envelope-projection.tsv')
        # Conservative integer-ms interior projection for the unchanged streaming
        # analyzer. This is NOT a process CPU counter file or a Java clock mapping.
        with projection.open('x') as stream:
            writer = csv.DictWriter(stream, fieldnames=['phase', 'operation', 'readEndEpochMillis',
                                                        'readStartEpochMillis'], delimiter='\t')
            writer.writeheader()
            for window in windows:
                lo, hi = math.ceil(window['startEpochMillis']), math.floor(window['endEpochMillis'])
                if lo >= hi:
                    raise ValueError('invocation too short for conservative millisecond projection')
                for phase, time in [('native-command-before', lo), ('native-command-after', hi)]:
                    writer.writerow(dict(phase=phase, operation=window['cycle'],
                                         readEndEpochMillis=time, readStartEpochMillis=time))
        thread_id = windows[0]['javaThreadId']
        dropped = {'nativeMethodSamples': 0, 'otherThreadExecutionOrAllocation': 0}

        def filtered(path):
            for event in reader.jfr_events(path):
                kind, value = event['type'], event['values']
                if kind == 'jdk.NativeMethodSample':
                    dropped['nativeMethodSamples'] += 1
                    continue
                if kind in ('jdk.ExecutionSample', 'jdk.ObjectAllocationSample'):
                    thread = value.get('sampledThread') or value.get('eventThread') or {}
                    if thread.get('javaThreadId') != thread_id:
                        dropped['otherThreadExecutionOrAllocation'] += 1
                        continue
                yield event

        ownership = old.analyze(args.recording, projection, filtered)
        report.update(status='PASS_SAME_RECORDING_NATIVE_EDT_OWNERSHIP_DIAGNOSTIC_ONLY',
                      ownership=ownership, excludedWholeRecordingEvents=dropped,
                      sampleScope='Native invocation EDT ExecutionSample/allocation events only; global GC summaries retained')
        report['inputPins'].update({str(p): sha(p) for p in (old_path, reader_path, projection)})
    if any(sha(Path(p)) != pin for p, pin in report['inputPins'].items()):
        raise ValueError('binding/analysis inputs changed')
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')
    print(report['status'])


if __name__ == '__main__':
    main()
