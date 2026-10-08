"""Read-only full-stack cache-refresh diagnostic; never grants acceptance."""
import argparse
import datetime
import json
from pathlib import Path
import subprocess
import tempfile

import importlib.util


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


MESH = 'com.live2d.graphics3d.editableMesh.GEditableMesh2'
ACTION = 'com.live2d.cubism.view.context.actionManager.'
CHAIN = [(MESH, 'updateIndices'), (MESH, 'updateMesh'),
         (MESH, 'getGlIndices'), (MESH, 'initByEditableMesh'),
         (ACTION + 'ac', 'a'), (ACTION + 'Z', 'aW'),
         ('com.live2d.cubism.view.context.action.action_meshEditor.'
          'CEAct_ModelingView_MeshEditor_Point_DragSelect$a', 'a'),
         (ACTION + 'CEActionManager', 'decideAction'),
         ('com.live2d.cubism.CEUpdateManager', 'repaintCanvas'),
         ('com.live2d.cubism.doc.modeling.CModelingEditMode_MeshEditor', 'commandAutoConnect'),
         ('com.live2d.cubism.CEAppCtrl', 'command_meshEditConnectAuto'),
         ('dev.turboism.validation.atlasimage.shadow.NativeObserverFreeAutoConnect', 'invokeMeasured'),
         ('dev.turboism.validation.atlasimage.shadow.NativeCommandJfrClock', 'invokeMeasured')]


def ordered_chain(frames):
    position = 0
    for frame in frames:
        if frame == CHAIN[position]:
            position += 1
            if position == len(CHAIN):
                return True
    return False


def audit(events, windows):
    if [w['cycle'] for w in windows] != [1, 2, 3]:
        raise ValueError('three ordered invocation windows required')
    if len({w['javaThreadId'] for w in windows}) != 1:
        raise ValueError('EDT identity changed')
    if any(w['startEpochMillis'] >= w['endEpochMillis'] for w in windows) or any(
            a['endEpochMillis'] >= b['startEpochMillis'] for a, b in zip(windows, windows[1:])):
        raise ValueError('invalid or overlapping windows')
    counts = {w['cycle']: {'executionSamples': 0, 'allocationSamples': 0,
                          'fullChainExecutionSamples': 0, 'fullChainAllocationSamples': 0,
                          'cacheRefreshSamples': 0, 'truncatedStacks': 0} for w in windows}
    excluded = {'outsideInvocation': 0, 'otherThread': 0}
    for event in events:
        kind, value = event['type'], event['values']
        if kind == 'jdk.DataLoss':
            raise ValueError('recording data loss')
        if kind not in ('jdk.ExecutionSample', 'jdk.ObjectAllocationSample'):
            continue
        time = datetime.datetime.fromisoformat(value['startTime']).timestamp() * 1000
        window = next((w for w in windows if w['startEpochMillis'] < time < w['endEpochMillis']), None)
        if window is None:
            excluded['outsideInvocation'] += 1
            continue
        thread = value.get('sampledThread') or value.get('eventThread') or {}
        if thread.get('javaThreadId') != window['javaThreadId']:
            excluded['otherThread'] += 1
            continue
        count = counts[window['cycle']]
        suffix = 'ExecutionSamples' if kind == 'jdk.ExecutionSample' else 'AllocationSamples'
        count[suffix[0].lower() + suffix[1:]] += 1
        stack = value.get('stackTrace') or {}
        count['truncatedStacks'] += int(bool(stack.get('truncated')))
        frames = [(f['method']['type']['name'].replace('/', '.'), f['method']['name'])
                  for f in stack.get('frames', [])]
        count['cacheRefreshSamples'] += int((MESH, 'updateIndices') in frames)
        if ordered_chain(frames):
            count['fullChain' + suffix] += 1
    if any(c['executionSamples'] == 0 for c in counts.values()):
        raise ValueError('missing native EDT execution samples')
    return {'cycles': counts, 'excludedSamples': excluded,
            'fullChainObservedEachCycle': all(c['fullChainExecutionSamples'] > 0 for c in counts.values())}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording', type=Path)
    parser.add_argument('boundaries', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--task-id', required=True)
    args = parser.parse_args()
    diag = Path(__file__).resolve().parent
    binding = module('binding', diag / 'analyze-jfr-native-invocations.py')
    reader = module('reader', diag.parent / 'analyze-resource-windows.py')
    paths = [args.recording, args.boundaries, Path(__file__),
             diag / 'analyze-jfr-native-invocations.py', diag.parent / 'analyze-resource-windows.py']
    pins = {str(p): reader.file_sha256(p) for p in paths}
    windows = binding.bind(args.recording, args.boundaries, args.task_id)
    with tempfile.TemporaryDirectory(prefix='native-cache-refresh-') as temp:
        samples = Path(temp) / 'samples.json'
        with samples.open('w') as stream:
            subprocess.run(['jfr', 'print', '--json', '--stack-depth', '64', '--events',
                            'jdk.ExecutionSample,jdk.ObjectAllocationSample,jdk.DataLoss',
                            str(args.recording)], stdout=stream, check=True)
        report = audit(reader.jfr_events(samples), windows)
        report['exportSha256'] = reader.file_sha256(samples)
    report.update(status='PASS_READ_ONLY_CACHE_REFRESH_STACK_AUDIT', taskId=args.task_id,
                  invocationWindows=windows, cpuPayloadFieldsMatched=30, stackDepth=64,
                  requiredOrderedFrames=CHAIN, inputPins=pins,
                  productionAcceptance='NOT_GRANTED', historicalVerdicts='UNCHANGED',
                  limitations=['Counts are sampled stacks, not exact calls, CPU time or allocated bytes.',
                               'Absence of sampled frames does not prove absence of execution.',
                               'This identifies a command-local caller path; input-state cause and T088 RSS cause remain unproven.',
                               'Output/cache parity and performance gates are independent; diagnostic failures remain failures.'])
    if any(reader.file_sha256(Path(p)) != pin for p, pin in pins.items()):
        raise ValueError('analysis input changed')
    with args.output.open('x') as stream:
        json.dump(report, stream, indent=2)
        stream.write('\n')
    print(json.dumps({k: report[k] for k in ['status', 'cycles', 'fullChainObservedEachCycle']}))


if __name__ == '__main__':
    main()
