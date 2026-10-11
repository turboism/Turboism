#!/usr/bin/env python3
"""Export a bounded, source-preserving Warp candidate for the native API probe.

This does not admit a GLB for production import or prove native visibility.
"""
import argparse,hashlib,json,struct
from pathlib import Path
import numpy as np

def export(source,out):
    report=json.loads((source/'report.json').read_text())
    assert report['status']=='BOUNDED_WARP_AUTHORING_FIXTURE_NOT_IMPORTED'
    meshes=[]
    for i in range(2):
        with np.load(source/f'mesh-{i}.npz') as d:
            meshes.append({k:d[k] for k in d.files})
    with np.load(source/'warp-keyforms.npz') as d:
        columns,rows=int(d['columns']),int(d['rows']);poses=d['poses'].copy();controls=d['controls'].copy()
    assert (columns,rows)==(238,119) and controls.shape==(289,28680,2)
    assert np.array_equal(poses,np.array([(y,p) for y in np.linspace(-30,30,17) for p in np.linspace(-30,30,17)],dtype='<f4'))
    assert controls.size//2<=10_000_000
    # Existing fixture is pivot-relative; the native Canvas uses the production frame center.
    controls=np.asarray(controls.astype(np.float64)+512,dtype='>f4')
    arrays=[]
    for m in meshes:
        assert len(m['source3D'])<=65535
        assert np.all(m['indices']<len(m['source3D']))
        arrays.extend((np.asarray(m[k],dtype=t) for k,t in (
            ('source3D','>f4'),('textureUV','>f4'),('warpLocal','>f4'),('indices','>i4'),
            ('sourceVertex','>i4'),('sourceUv','>i4'),('slotIdentity','>i4'),('sourceTriangle','>i4'))))
    assert sum(len(m['indices']) for m in meshes)==150784
    assert all(np.all(np.isfinite(m[k])) for m in meshes for k in ('source3D','textureUV','warpLocal'))
    assert np.all(np.isfinite(controls))
    out.mkdir(parents=True,exist_ok=False)
    file=out/'apple-warp.bin'
    with file.open('xb') as f:
        f.write(b'MI3DAW01');f.write(struct.pack('>4i',columns,rows,len(meshes),len(poses)))
        for m in meshes:f.write(struct.pack('>2i',len(m['source3D']),len(m['indices'])))
        for a in arrays:f.write(a.tobytes())
        f.write(np.asarray(poses,dtype='>f4').tobytes());f.write(controls.tobytes())
    result={'status':'NATIVE_WARP_CANDIDATE_NOT_IMPORTED','bytes':file.stat().st_size,
        'sha256':hashlib.sha256(file.read_bytes()).hexdigest(),'columns':columns,'rows':rows,'poses':289,
        'controlSamples':controls.size//2,'sourceTriangles':150784,'sourceUVUnchanged':True,
        'canvasCenter':[512,512],'materials':'SOURCE_OBJ_HAS_NO_MATERIAL','hostExecuted':False,
        'inputs':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in source.glob('*') if p.is_file()}}
    (out/'manifest.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--out',type=Path,required=True)
    a=p.parse_args();export(a.source,a.out)
