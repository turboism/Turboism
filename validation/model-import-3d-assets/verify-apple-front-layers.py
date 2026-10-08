#!/usr/bin/env python3
"""Independent source-space rays verify front-layer raster witnesses, not import."""
import argparse,hashlib,json,struct
from pathlib import Path
import numpy as np

def verify(source,reference):
    raw=source.read_bytes();report=json.loads((reference/'report.json').read_text())
    assert hashlib.sha256(raw).hexdigest()==report['sourceSha256']
    n=struct.unpack_from('<I',raw,12)[0];doc=json.loads(raw[20:20+n]);binary=raw[28+n:]
    def accessor(i,dim,dtype):
        a=doc['accessors'][i];v=doc['bufferViews'][a['bufferView']]
        return np.frombuffer(binary,dtype,a['count']*dim,v.get('byteOffset',0)+a.get('byteOffset',0)).reshape(-1,dim)
    meshes=[]
    for m in doc['meshes']:
        p=m['primitives'][0];v=accessor(p['attributes']['POSITION'],3,'<f4').astype(float);ix=accessor(p['indices'],1,'<u4').reshape(-1,3);meshes.append(v[ix])
    cam=report['camera'];center=np.array(cam['center']);scale=cam['scale'];size=cam['resolution'][0];radius=160/scale;checks=[]
    for row in report['rows']:
        y,p=np.radians([row['yaw'],row['pitch']]);cy,sy=np.cos(y),np.sin(y);cp,sp=np.cos(p),np.sin(p)
        ry=np.array([[cy,0,sy],[0,1,0],[-sy,0,cy]]);rx=np.array([[1,0,0],[0,cp,-sp],[0,sp,cp]]);inverse=(ry@rx).T;direction=inverse@np.array([0.,0.,1.])
        triangles=meshes[row['component']];a=triangles[:,0];e1=triangles[:,1]-a;e2=triangles[:,2]-a
        h=np.cross(direction,e2);det=np.einsum('ij,ij->i',e1,h);inv=np.divide(1.,det,out=np.zeros_like(det),where=det>1e-12)
        for w in row['witnesses']:
            x,yy=w['pixel'];origin=center+inverse@np.array([(x+.5-size/2)/scale,-(yy+.5-size/2)/scale,-2*radius])
            s=origin-a;u=inv*np.einsum('ij,ij->i',s,h);q=np.cross(s,e1);v=inv*(q@direction);t=inv*np.einsum('ij,ij->i',e2,q)
            valid=(det>1e-12)&(u>=-1e-10)&(v>=-1e-10)&(u+v<=1+1e-10)&(t>=0)
            assert valid.sum()>=2
            assert valid[w['nearestTriangle']] and valid[w['farthestTriangle']]
            found=[float(t[valid].min()-2*radius),float(t[valid].max()-2*radius)]
            error=max(abs(a-b) for a,b in zip(found,w['depths']));assert error<1e-6,(row,w,error)
            assert found[1]-found[0]>report['minimumDistinctLayerGap']
            checks.append(dict(component=row['component'],pose=[row['yaw'],row['pitch']],pixel=w['pixel'],frontHits=int(valid.sum()),depths=found,maxError=error))
    result=dict(status='FRONT_LAYER_RAY_WITNESSES_PASS_NOT_IMPORTED',sourceSha256=report['sourceSha256'],checks=checks,hostExecuted=False)
    (reference/'ray-verification.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--reference',type=Path,required=True);a=p.parse_args();verify(a.source,a.reference)
