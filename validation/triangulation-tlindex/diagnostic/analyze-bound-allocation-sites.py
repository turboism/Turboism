"""Stream allocation samples bound to same-recording native EDT envelopes."""
import argparse
from collections import Counter
import datetime
import importlib.util
import json
from pathlib import Path
import subprocess


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def summarize(events, windows, chain):
    if [w['cycle'] for w in windows] != [1, 2, 3] or len({w['javaThreadId'] for w in windows}) != 1:
        raise ValueError('three ordered scopes on one EDT required')
    if any(w['startEpochMillis'] >= w['endEpochMillis'] for w in windows) or any(a['endEpochMillis'] >= b['startEpochMillis'] for a, b in zip(windows, windows[1:])):
        raise ValueError('invalid invocation windows')
    counters = [{k: Counter() for k in ['counts', 'weights', 'classes', 'sites', 'partition', 'inclusive', 'partitionCounts']} for _ in windows]
    excluded = Counter()
    for event in events:
        if event['type'] == 'jdk.DataLoss':
            raise ValueError('recording data loss')
        if event['type'] != 'jdk.ObjectAllocationSample':
            raise ValueError('unsupported allocation export event')
        value = event['values']; weight = value['weight']
        if type(weight) is not int or weight < 0:
            raise ValueError('invalid allocation weight')
        stamp = datetime.datetime.fromisoformat(value['startTime']).timestamp() * 1000
        index = next((i for i,w in enumerate(windows) if w['startEpochMillis'] < stamp < w['endEpochMillis']), None)
        if index is None:
            excluded['outsideInvocationSamples'] += 1
            excluded['outsideInvocationWeightBytes'] += weight
            continue
        if (value.get('eventThread') or {}).get('javaThreadId') != windows[index]['javaThreadId']:
            excluded['otherThreadSamples'] += 1
            excluded['otherThreadWeightBytes'] += weight
            continue
        trace = value.get('stackTrace') or {}
        frames = trace.get('frames', [])
        pairs = [((f.get('method',{}).get('type') or {}).get('name','').replace('/', '.'), f.get('method',{}).get('name','')) for f in frames]
        names = [owner + '.' + name for owner,name in pairs]
        full_chain = chain.ordered_chain(pairs)
        cache = (chain.MESH, 'updateIndices') in pairs
        tri = any(n.startswith('com.live2d.graphics3d.editableMesh.triangulation.') for n in names)
        helpers = {kind: any(n.startswith('dev.turboism.adapter.cubism.mesh.' + kind + '.') for n in names)
                   for kind in ['TriangulationEdgeIndex', 'NativeMeshEdgeLookup', 'NativeMeshEdgeTable']}
        # Disjoint by observed caller precedence; inclusive owners remain separate.
        partition = ('fullRedrawCacheChain' if full_chain else 'otherCacheRefresh' if cache else
                     'otherTriangulation' if tri else 'helperOnly' if any(helpers.values()) else 'otherOrUnknown')
        c = counters[index];c['counts']['samples'] += 1;c['weights']['all'] += weight
        for name,seen in [('emptyStack', not frames), ('truncatedStack', bool(trace.get('truncated')))]:
            c['counts'][name] += int(seen);c['weights'][name] += weight * int(seen)
        cls = (value.get('objectClass') or {}).get('name', '<unknown>').replace('/', '.')
        c['classes'][cls] += weight;c['partition'][partition] += weight;c['partitionCounts'][partition] += 1
        for name,seen in [('triangulation',tri), ('cacheRefresh',cache), ('fullRedrawCacheChain',full_chain), *helpers.items()]:
            c['inclusive'][name] += weight * int(seen)
        sites = tuple((owner + '.' + name, (f.get('method') or {}).get('descriptor',''), f.get('bytecodeIndex')) for (owner,name),f in zip(pairs,frames))
        c['sites'][(cls,partition,sites,bool(trace.get('truncated')))] += weight
    cycles=[]
    for window,c in zip(windows,counters):
        if c['counts']['samples'] == 0:
            raise ValueError('missing native EDT allocation samples')
        if any(sum(c[key].values()) != c['weights']['all'] for key in ['classes','partition','sites']):
            raise ValueError('allocation partition accounting mismatch')
        sites=[dict(objectClass=cls,observedCallerPartition=part,frames=[dict(method=name,descriptor=desc,bci=bci) for name,desc,bci in frames],truncated=trunc,sampledWeightBytes=weight) for (cls,part,frames,trunc),weight in c['sites'].most_common()]
        cycles.append(dict(cycle=window['cycle'],sampleCounts=dict(c['counts']),sampledWeightBytes=dict(c['weights']),classes=dict(c['classes'].most_common()),observedCallerPartitionWeightBytes=dict(c['partition']),observedCallerPartitionSamples=dict(c['partitionCounts']),inclusiveOwnerWeightBytes=dict(c['inclusive']),sites=sites))
    return dict(cycles=cycles,excluded=dict(excluded))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('recording',type=Path);parser.add_argument('boundaries',type=Path);parser.add_argument('output',type=Path);parser.add_argument('--task-id',required=True)
    args=parser.parse_args();diag=Path(__file__).resolve().parent
    binding=module('binding',diag/'analyze-jfr-native-invocations.py');reader=module('reader',diag.parent/'analyze-resource-windows.py');chain=module('chain',diag/'analyze-native-cache-refresh.py')
    paths=[args.recording,args.boundaries,Path(__file__),diag/'analyze-jfr-native-invocations.py',diag/'analyze-native-cache-refresh.py',diag.parent/'analyze-resource-windows.py']
    pins={str(p):reader.file_sha256(p) for p in paths};windows=binding.bind(args.recording,args.boundaries,args.task_id)
    proc=subprocess.Popen(['jfr','print','--json','--stack-depth','64','--events','jdk.ObjectAllocationSample,jdk.DataLoss',str(args.recording)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True)
    class Pipe:
        def open(self): return proc.stdout
    try:
        report=summarize(reader.jfr_events(Pipe()),windows,chain)
        error=proc.stderr.read()
        if proc.wait(timeout=10) != 0: raise ValueError('JFR export failed: '+error[-1000:])
    finally:
        if proc.poll() is None:proc.terminate();proc.wait(timeout=10)
    if any(reader.file_sha256(Path(p)) != h for p,h in pins.items()):raise ValueError('analysis inputs changed')
    report.update(status='PASS_SAME_JFR_EDT_ALLOCATION_SITES_DIAGNOSTIC_ONLY',taskId=args.task_id,invocationWindows=windows,cpuPayloadFieldsMatched=30,inputPins=pins,performanceAcceptance='NOT_GRANTED',limitations=['Sample weights are estimates, not exact allocated/live bytes, calls or CPU fractions.','Strict event-time envelope membership does not prove weighted bytes were allocated wholly within that envelope.','Observed caller partitions use full redraw chain > other cache refresh > triangulation > helper > unknown; inclusive owner weights overlap.','Truncated/inlined/missing frames limit attribution; absence of a sampled owner is not execution absence.','Independent historical recordings do not establish paired formal gain or explain T101 causally.'])
    with args.output.open('x') as f:json.dump(report,f,indent=2);f.write('\n')
    print(report['status'])
    for c in report['cycles']:print(c['cycle'],c['sampledWeightBytes'],c['observedCallerPartitionWeightBytes'])


if __name__ == '__main__':main()
