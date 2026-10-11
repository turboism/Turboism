"""Measure coincident source slots across native chart captures; excludes visibility and texture seams."""
import argparse
import hashlib
import json
from pathlib import Path
import runpy
import numpy as np

HERE=Path(__file__).resolve().parent
APPLE=runpy.run_path(str(HERE/'verify-apple-xyz.py'))
require=APPLE['require']


def measure(charts,report):
    meshes=report['imported']['meshes']
    require(len(charts)==len(meshes)>0,'boundary chart inventory')
    for chart,mesh in zip(charts,meshes):
        require(chart['count']==mesh['vertexCount'] and chart['geometrySha256']==mesh['geometrySha256'],
            'boundary source/UV/index identity')
    xyz=np.concatenate([c['xyz'] for c in charts])
    require(xyz.shape==(sum(c['count'] for c in charts),3) and np.all(np.isfinite(xyz)),'finite boundary source slots')
    _,inverse=np.unique(xyz,axis=0,return_inverse=True)
    order=np.argsort(inverse,kind='stable');boundaries=np.flatnonzero(np.diff(inverse[order]))+1
    pairs=[]
    for group in np.split(order,boundaries):
        for i in range(len(group)):
            for j in range(i+1,len(group)):
                pairs.append((int(group[i]),int(group[j])))
                require(len(pairs)<=1_000_000,'coincident source pair budget')
    require(bool(pairs),'source has no shared boundary witnesses')
    left,right=np.array(pairs).T;rows=[]
    chart_slots=np.concatenate([np.full(c['count'],i) for i,c in enumerate(charts)])
    for frame in report['frames']:
        require(len(frame['evaluated'])==len(meshes),'complete native boundary capture')
        arrays=[]
        for captured,mesh in zip(frame['evaluated'],meshes):
            require(captured['id']==mesh['id'],'boundary captured mesh identity')
            points=np.array(captured['positions'],dtype=float)
            require(points.shape==(mesh['vertexCount'],2) and np.all(np.isfinite(points)),'finite complete boundary projection')
            arrays.append(points)
        points=np.concatenate(arrays);distance=np.linalg.norm(points[left]-points[right],axis=1);i=int(np.argmax(distance))
        rows.append(dict(poseXYZ=frame['poseXYZ'],maximumSharedSourceSeparationPixels=float(distance[i]),
            witnessSourceXYZ=xyz[left[i]].tolist(),sourceSlots=[int(left[i]),int(right[i])],
            charts=[int(chart_slots[left[i]]),int(chart_slots[right[i]])],
            separatedPairsAbove001=int(np.count_nonzero(distance>.001))))
    require(bool(rows),'native boundary pose inventory')
    return dict(sourceChartGeometryHashesExact=True,sourceSlots=len(xyz),sharedPairs=len(pairs),
        maximumSharedSourceSeparationPixels=max(r['maximumSharedSourceSeparationPixels'] for r in rows),poses=rows,
        scope='Coincident source slot separation in native calculated world positions; includes chart and UV duplicates. '
            'Does not prove which edges are visible, texture sampling, antialias behavior or occlusion.')


def run(args):
    report=json.loads(args.report.read_text());audit=json.loads(args.source_audit.read_text());manifest=json.loads(args.manifest.read_text())
    require(report['status']=='PASS' and report['framesCaptured'] is True and report['inputSha256']==APPLE['SOURCE_SHA'],
        'authorized native apple capture')
    require([f['poseXYZ'] for f in report['frames']]==[list(p) for p in APPLE['POSES']],'complete XYZ boundary poses')
    require(audit['sourceDataExact'] is True and audit['triangles']==150784,'independent source audit')
    require(manifest['independent-source-report.json']==hashlib.sha256(args.source_audit.read_bytes()).hexdigest(),'frozen source audit')
    entry=manifest.get('prepared/packed.bin') or manifest.get(str(args.packed))
    if isinstance(entry,dict):entry=entry.get('sha256')
    require(entry==hashlib.sha256(args.packed.read_bytes()).hexdigest(),'frozen source chart identity')
    if args.maximum is not None:require(np.isfinite(args.maximum) and args.maximum>0,'positive finite boundary budget')
    result=measure(APPLE['source_charts'](args.packed),report)
    result['status']='NATIVE_BOUNDARY_MEASURED'
    if args.maximum is not None:
        passed=result['maximumSharedSourceSeparationPixels']<=args.maximum
        result.update(status='NATIVE_BOUNDARY_PASS' if passed else 'NATIVE_BOUNDARY_FAIL',maximumAllowedPixels=args.maximum)
    result['inputs']={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in (args.report,args.packed,args.source_audit,args.manifest)}
    args.out.write_text(json.dumps(result,indent=2)+'\n')
    print(result['status'],result['maximumSharedSourceSeparationPixels'])
    return 1 if result['status']=='NATIVE_BOUNDARY_FAIL' else 0


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('report','packed','source-audit','manifest','out'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--maximum',type=float)
    raise SystemExit(run(p.parse_args()))
