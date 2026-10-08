#!/usr/bin/env python3
"""Freeze a bounded native-authoring candidate, retaining every original source corner.

This is an offline fixture, not import admission or a Cubism document. Original
geometry/UV and chart provenance remain alongside derived normalized Warp inputs.
"""
import argparse, hashlib, json
from pathlib import Path
import numpy as np

def run(a):
    fit_report=json.loads((a.fit/'report.json').read_text());kernel=fit_report['officialJavaKernelOffline']
    assert kernel['cellsPerChart']==16 and kernel['parameterKeysPerAxis']==17
    assert fit_report['scale']==2.954462184876577
    allocation=next(r for r in fit_report['combinedAllocations'] if r['keysPerAxis']==17)
    if allocation['controlSamples']>a.max_samples:
        raise ValueError('control sample budget exceeded before authoring fixture allocation')
    charts=[]
    for i in range(2):
        with np.load(a.charts/f'component-{i}.npz') as data:
            charts.append({key:data[key] for key in data.files})
    counts=[len(d['chart']) for d in charts];offsets=np.cumsum([0]+counts)
    order=np.fromfile(a.order,dtype='<i4');assert np.array_equal(np.sort(order),np.arange(sum(counts)))
    a.out.mkdir(parents=True,exist_ok=False)
    cells=16;columns=238;rows=119
    patches=[];slots={};keys=[];triangles=[];indices=[]
    def flush():
        if not triangles:return
        source=[];uv=[];unfold=[];source_vertex=[];source_uv=[]
        for component,slot,chart,face,corner in keys:
            d=charts[component];p=d['parameter'][face,corner]
            source.append(d['source3D'][face,corner]);uv.append(d['textureUV'][face,corner])
            source_vertex.append(d['sourceVertex'][face,corner]);source_uv.append(d['sourceUv'][face,corner])
            unfold.append((p*cells+[(chart%14)*(cells+1),(chart//14)*(cells+1)])/[columns,rows])
        source=np.array(source,dtype='<f4');uv=np.array(uv,dtype='<f4');unfold=np.array(unfold,dtype='<f4')
        source_vertex=np.array(source_vertex,dtype='<u4');source_uv=np.array(source_uv,dtype='<u4')
        local_indices=np.array(indices,dtype='<u4').reshape(-1,3)
        # Independently verify every packed triangle corner against original chart sidecar.
        for i,global_id in enumerate(triangles):
            component=int(global_id>=offsets[1]);face=int(global_id-offsets[component]);d=charts[component]
            assert np.array_equal(source[local_indices[i]],d['source3D'][face])
            assert np.array_equal(uv[local_indices[i]],d['textureUV'][face])
            assert np.array_equal(source_vertex[local_indices[i]],d['sourceVertex'][face])
            assert np.array_equal(source_uv[local_indices[i]],d['sourceUv'][face])
        file=a.out/f'mesh-{len(patches)}.npz'
        np.savez_compressed(file,source3D=source,textureUV=uv,warpLocal=unfold,indices=local_indices,
            sourceVertex=source_vertex,sourceUv=source_uv,
            slotIdentity=np.array([k[:3] for k in keys],dtype='<i4'),sourceTriangle=np.array(triangles,dtype='<i4'))
        patches.append(dict(file=str(file),vertices=len(keys),triangles=len(triangles)))
        slots.clear();keys.clear();triangles.clear();indices.clear()
    for global_id in order:
        component=int(global_id>=offsets[1]);face=int(global_id-offsets[component]);d=charts[component];chart=int(d['chart'][face])
        identities=[(component,int(slot),chart) for slot in d['glbSlot'][face]]
        additions=sum(identity not in slots for identity in set(identities))
        if slots and len(slots)+additions>65535:flush()
        triangles.append(int(global_id))
        for corner,identity in enumerate(identities):
            if identity not in slots:slots[identity]=len(keys);keys.append((*identity,face,corner))
            indices.append(slots[identity])
    flush()
    assert sum(p['triangles'] for p in patches)==150784
    stored=np.concatenate([np.load(p['file'])['sourceTriangle'] for p in patches]);assert np.array_equal(stored,order)
    cage=np.load(a.fit/'fit-16.npz');pivot=cage['pivot'];scale=float(cage['scale'])
    atlas=np.zeros((rows+1,columns+1,3))
    for chart,controls in enumerate(cage['controls3D']):
        x=(chart%14)*17;y=(chart//14)*17;atlas[y:y+17,x:x+17]=controls.reshape(17,17,3)
    keys=np.linspace(-30,30,17,dtype='<f4');poses=np.array([(y,p) for y in keys for p in keys],dtype='<f4')
    controls=[];source=atlas.reshape(-1,3)-pivot
    for yaw,pitch in poses:
        y,p=np.deg2rad(np.array([yaw,pitch],dtype=float));rx=np.array([[1,0,0],[0,np.cos(p),-np.sin(p)],[0,np.sin(p),np.cos(p)]])
        ry=np.array([[np.cos(y),0,np.sin(y)],[0,1,0],[-np.sin(y),0,np.cos(y)]])
        projected=source@(ry@rx).T;controls.append(projected[:,:2]*[scale,-scale])
    controls=np.array(controls,dtype='<f4');assert controls.size//2==allocation['controlSamples'] and np.all(np.isfinite(controls))
    np.savez_compressed(a.out/'warp-keyforms.npz',poses=poses,controls=controls,rows=rows,columns=columns,quadTransform=True,pivot=pivot,scale=scale)
    inputs=[a.charts/'report.json',a.charts/'component-0.npz',a.charts/'component-1.npz',a.fit/'report.json',a.fit/'fit-16.npz',a.order,Path(__file__)]
    report=dict(status='BOUNDED_WARP_AUTHORING_FIXTURE_NOT_IMPORTED',inputsSha256={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs},
        patches=patches,totalSourceTriangles=150784,sourceTriangleSequenceExact=True,originalPositionAndUvCornersExact=True,
        warpInputCoordinatesDerivedNotOriginal=True,allocation=allocation,maxControlSamples=a.max_samples,
        quadTransform=True,ellipticalInterpolation=False,hostExecuted=False,
        remainingGates=['explicit stored Warp keyform target authoring','native triangle order and culling','native parameter evaluation','save/reopen/Undo/Redo','official no-plugin export'])
    (a.out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--charts',type=Path,required=True);p.add_argument('--fit',type=Path,required=True)
    p.add_argument('--order',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--max-samples',type=int,default=10_000_000)
    run(p.parse_args())
