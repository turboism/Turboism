#!/usr/bin/env python3
"""Exact high-resolution GLB triangle/UV extraction for the offline Java BSP probe.

Does not route around production import admission: no host or importer is called.
"""
import argparse,hashlib,json,struct
from pathlib import Path
import numpy as np
SHA='54331e29af9a3ca07f11017712ce9156208a8ae8538b0a80b78e8c5eb8a894e0'
def run(source,out):
    raw=source.read_bytes();assert hashlib.sha256(raw).hexdigest()==SHA
    length,kind=struct.unpack_from('<II',raw,12);assert kind==0x4e4f534a
    doc=json.loads(raw[20:20+length]);binary=raw[28+length:]
    def data(index,dim,dtype):
        a=doc['accessors'][index];v=doc['bufferViews'][a['bufferView']]
        return np.frombuffer(binary,dtype=dtype,count=a['count']*dim,offset=v.get('byteOffset',0)+a.get('byteOffset',0)).reshape(-1,dim)
    target=bytearray(b'BSP1'+struct.pack('<I',len(doc['meshes'])));counts=[]
    for mesh in doc['meshes']:
        p=mesh['primitives'][0];v=data(p['attributes']['POSITION'],3,'<f4');uv=data(p['attributes']['TEXCOORD_0'],2,'<f4')
        indices=data(p['indices'],1,'<u4').reshape(-1,3);corners=np.concatenate([v,uv],axis=1)[indices]
        target+=struct.pack('<I',len(indices))+corners.astype('<f4').tobytes();counts.append(len(indices))
    out.mkdir(parents=True,exist_ok=False);(out/'source-triangles.bin').write_bytes(target)
    report=dict(status='EXACT_SOURCE_TRIANGLES_FOR_OFFLINE_BSP_ONLY',sourceSha256=SHA,binarySha256=hashlib.sha256(target).hexdigest(),
        trianglesPerMesh=counts,geometryAndUvs='Exact float32 source corners, original triangle order and 0-2 quad diagonal',hostExecuted=False)
    (out/'input.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();run(a.source,a.out)
