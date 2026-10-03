"""Resolve caller chains from an already bound native-command JFR recording.

Counts are sampled stacks, not CPU durations or causal performance gains. This
separate analysis preserves the original ownership reports and their parsers.
"""
import argparse
from collections import Counter
import datetime
import importlib.util
import json
import math
from pathlib import Path
import subprocess


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def summarize(events, windows, frame_name):
    if ([w['cycle'] for w in windows] != [1, 2, 3]
            or len({w['javaThreadId'] for w in windows}) != 1):
        raise ValueError('three ordered same-EDT invocation windows required')
    intervals = [(math.ceil(w['startEpochMillis']), math.floor(w['endEpochMillis'])) for w in windows]
    if (any(lo >= hi for lo, hi in intervals)
            or any(hi >= lo for (_, hi), (lo, _) in zip(intervals, intervals[1:]))):
        raise ValueError('invalid conservative invocation intervals')
    thread_id = windows[0]['javaThreadId']
    counters = [{key: Counter() for key in ('scopes', 'leaf', 'callers', 'settleCallers', 'checkIndexCallers')}
                for _ in windows]
    exclusions = Counter()
    for event in events:
        if event['type'] != 'jdk.ExecutionSample':
            exclusions['nonExecutionEvents'] += 1
            continue
        value = event['values']
        thread = value.get('sampledThread') or {}
        if thread.get('javaThreadId') != thread_id:
            exclusions['otherThreadExecutionSamples'] += 1
            continue
        epoch = datetime.datetime.fromisoformat(value['startTime']).timestamp() * 1000
        index = next((i for i, (lo, hi) in enumerate(intervals) if lo <= epoch <= hi), None)
        if index is None:
            exclusions['outsideInvocationExecutionSamples'] += 1
            continue
        trace = value.get('stackTrace') or {}
        frames = [frame_name(frame) for frame in trace.get('frames', [])]
        leaf = frames[0] if frames else '<unknown>'
        chain = ' <- '.join(frames[:8]) if frames else '<unknown>'
        c = counters[index]
        c['scopes']['all'] += 1
        c['scopes']['indexInclusive'] += int(any(frame.startswith(
            'dev.turboism.adapter.cubism.mesh.TriangulationEdgeIndex') for frame in frames))
        c['scopes']['triangulationInclusive'] += int(any(frame.startswith(
            'com.live2d.graphics3d.editableMesh.triangulation.') for frame in frames))
        c['scopes']['emptyStack'] += int(not frames)
        c['scopes']['truncatedStack'] += int(bool(trace.get('truncated')))
        c['leaf'][leaf] += 1
        c['callers'][chain] += 1
        if leaf.startswith('dev.turboism.adapter.cubism.mesh.TriangulationEdgeIndex.settle('):
            c['settleCallers'][chain] += 1
        if leaf.startswith('java.util.Objects.checkIndex('):
            c['checkIndexCallers'][chain] += 1
    cycles = []
    for i, c in enumerate(counters):
        if not c['scopes']['all']:
            raise ValueError('invocation has no bound EDT execution samples')
        if sum(c['leaf'].values()) != c['scopes']['all'] or sum(c['callers'].values()) != c['scopes']['all']:
            raise ValueError('sample accounting mismatch')
        cycles.append({'cycle': i + 1, 'sampleScopes': dict(c['scopes']),
                       'cpuLeafCounts': dict(c['leaf']),
                       'cpuLeafSites': c['leaf'].most_common(40),
                       'cpuCallerSites': c['callers'].most_common(60),
                       'settleLeafCallerSites': c['settleCallers'].most_common(),
                       'checkIndexLeafCallerSites': c['checkIndexCallers'].most_common()})
    return {'cycles': cycles, 'excludedExecutionSamples': dict(exclusions)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('binding_report', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    prior = json.loads(args.binding_report.read_text())
    if prior['status'] != 'PASS_SAME_RECORDING_NATIVE_EDT_OWNERSHIP_DIAGNOSTIC_ONLY':
        raise ValueError('successful same-recording ownership report required')
    diag = Path(__file__).resolve().parent
    binder = module('binding', diag / 'analyze-jfr-native-invocations.py')
    owner = module('ownership', diag / 'analyze-production-ownership.py')
    reader_path = diag.parent / 'analyze-resource-windows.py'
    reader = module('reader', reader_path)
    for name, expected in prior['inputPins'].items():
        if binder.sha(Path(name)) != expected:
            raise ValueError('original bound input changed: ' + name)
    recordings = [Path(name) for name in prior['inputPins'] if name.endswith('.jfr')]
    boundaries = [Path(name) for name in prior['inputPins'] if name.endswith('observer-free-command-cpu.tsv')]
    if len(recordings) != 1 or len(boundaries) != 1:
        raise ValueError('one original recording and independent CPU boundary file required')
    windows = binder.bind(recordings[0], boundaries[0], prior['taskId'])
    if windows != prior['invocationWindows']:
        raise ValueError('original binding no longer matches')
    pins = dict(prior['inputPins'])
    for path in (args.binding_report, Path(__file__), reader_path):
        pins[str(path)] = binder.sha(path)
    process = subprocess.Popen(['jfr', 'print', '--json', '--stack-depth', '64',
                                '--events', 'jdk.ExecutionSample', str(recordings[0])],
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    try:
        report = summarize(reader.jfr_events(owner.Pipe(process.stdout)), windows, owner.frame_name)
        error = process.stderr.read()
        if process.wait(timeout=10) != 0:
            raise ValueError('JFR export failed: ' + error[-1000:])
    finally:
        if process.poll() is None:
            process.terminate()
            process.wait(timeout=10)
    for current, original in zip(report['cycles'], prior['ownership']['cycles']):
        if current['sampleScopes'] != original['cpuSampleScopes']:
            raise ValueError('CPU scope differs from original bound ownership')
        if dict(current['cpuLeafSites']) != dict(original['cpuLeafSites']):
            raise ValueError('CPU leaf accounting differs from original bound ownership: '
                             + json.dumps({'cycle': current['cycle'],
                                           'originalTop40': original['cpuLeafSites'],
                                           'currentTop40': current['cpuLeafSites'],
                                           'currentAllCounts': current['cpuLeafCounts']}))
    if any(binder.sha(Path(name)) != expected for name, expected in pins.items()):
        raise ValueError('bound input changed during analysis')
    report.update(status='PASS_BOUND_NATIVE_EDT_CPU_CALLER_DIAGNOSTIC_ONLY',
                  taskId=prior['taskId'], invocationWindows=windows, cpuPayloadFieldsMatched=30,
                  inputPins=pins, performanceAcceptance='NOT_GRANTED',
                  limitations=['Execution samples are observed stacks, not CPU durations or causal gains.',
                               'Caller chains retain at most eight frames; inlining/truncation limits attribution.',
                               'Only ExecutionSample events are exported; native event-loop samples are not CPU evidence.',
                               'This recording uses T057, predating T075 QuerySnapshot and T077 settlement deferral.',
                               'Historical clock-unproven reports and failed performance gates remain unchanged.'])
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')
    print(report['status'])


if __name__ == '__main__':
    main()
