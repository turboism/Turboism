#!/usr/bin/env python3
"""Cross-check raster witnesses using independent Moller-Trumbore source-space rays."""
from pathlib import Path
import argparse,hashlib,json,struct
import numpy as np

def verify(source,reference):
    raw=source.read_bytes();report=json.loads((reference/'reference.json').read_text())
    assert report['sourceSha256']==hashlib.sha256(raw).hexdigest()
    n=struct.unpack_from('<I',raw,12)[0];doc=json.loads(raw[20:20+n]);binary=raw[28+n:]
    def accessor(i,dim,dtype):
        a=doc['accessors'][i];v=doc['bufferViews'][a['bufferView']]
        return np.frombuffer(binary,dtype=dtype,count=a['count']*dim,offset=v.get('byteOffset',0)+a.get('byteOffset',0)).reshape(-1,dim)
    meshes=[]
    for mesh in doc['meshes']:
        p=mesh['primitives'][0];v=accessor(p['attributes']['POSITION'],3,'<f4').astype(float);ix=accessor(p['indices'],1,'<u4').reshape(-1,3);meshes.append(v[ix])
    center=np.array(report['center']);scale=report['scale'];size=report['resolution'][0];radius=160/scale;checks=[]
    for pose in report['poses']:
        y,p=np.radians([pose['yaw'],pose['pitch']]);cy,sy=np.cos(y),np.sin(y);cp,sp=np.cos(p),np.sin(p)
        ry=np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]]);rx=np.array([[1,0,0],[0,cp,-sp],[0,sp,cp]]);inverse=(ry@rx).T;direction=inverse@np.array([0.,0.,1.])
        for label in ('component0FrontWitness','component1FrontWitness'):
            w=pose[label]
            if w is None:continue
            x,yy=w['pixel'];origin=center+inverse@np.array([(x+.5-size/2)/scale,-(yy+.5-size/2)/scale,-2*radius]);found=[]
            for triangles in meshes:
                a=triangles[:,0];e1=triangles[:,1]-a;e2=triangles[:,2]-a;h=np.cross(direction,e2);det=np.einsum('ij,ij->i',e1,h)
                inv=np.divide(1.,det,out=np.zeros_like(det),where=np.abs(det)>1e-12);s=origin-a;u=inv*np.einsum('ij,ij->i',s,h);q=np.cross(s,e1);v=inv*(q@direction);t=inv*np.einsum('ij,ij->i',e2,q)
                valid=(abs(det)>1e-12)&(u>=-1e-10)&(v>=-1e-10)&(u+v<=1+1e-10)&(t>=0)
                assert valid.any();found.append(float(t[valid].min()-2*radius))
            error=max(abs(a-b) for a,b in zip(found,w['depths']));assert error<1e-6,(pose,label,error)
            checks.append(dict(pose=[pose['yaw'],pose['pitch']],pixel=w['pixel'],depths=found,maxError=error))
    assert any(p['singleWholeComponentOrderInsufficient'] for p in report['poses'])
    result=dict(status='RAY_WITNESSES_PASS_NOT_NATIVE_IMPORT',checks=checks,hostExecuted=False)
    (reference/'ray-verification.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--reference',type=Path,required=True);a=p.parse_args();verify(a.source,a.reference)
