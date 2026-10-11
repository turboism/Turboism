#!/usr/bin/env python3
"""Locate multi-front-layer pixels in original source geometry, not native admission.

Two or more front-facing hits must differ by >1e-5 source units; shared-edge and
coplanar depth ties do not count. No image boundary mask or model reduction.
"""
import argparse,hashlib,json,struct
from pathlib import Path
import numpy as np
from PIL import Image
SHA='54331e29af9a3ca07f11017712ce9156208a8ae8538b0a80b78e8c5eb8a894e0'
def run(source,reference,out):
    raw=source.read_bytes();assert hashlib.sha256(raw).hexdigest()==SHA
    length=struct.unpack_from('<I',raw,12)[0];doc=json.loads(raw[20:20+length]);blob=raw[28+length:]
    ref=json.loads((reference/'reference.json').read_text());assert ref['sourceSha256']==SHA
    def data(index,dim,dtype):
        a=doc['accessors'][index];v=doc['bufferViews'][a['bufferView']]
        return np.frombuffer(blob,dtype,a['count']*dim,v.get('byteOffset',0)+a.get('byteOffset',0)).reshape(-1,dim).copy()
    meshes=[]
    for m in doc['meshes']:
        p=m['primitives'][0];v=data(p['attributes']['POSITION'],3,'<f4').astype(float);t=data(p['indices'],1,'<u4').reshape(-1,3)
        q=v[t]-v.mean(axis=0);volume=np.einsum('ij,ij->i',q[:,0],np.cross(q[:,1],q[:,2])).sum()/6
        assert volume>0;meshes.append((v,t,float(volume)))
    out.mkdir(parents=True,exist_ok=False);rows=[];size=ref['resolution'][0]
    for pose in ref['poses']:
        yaw,pitch=pose['yaw'],pose['pitch'];y,p=np.radians([yaw,pitch]);cy,sy=np.cos(y),np.sin(y);cp,sp=np.cos(p),np.sin(p)
        rotation=np.array([[cy,sy*sp,sy*cp],[0,cp,-sp],[-sy,cy*sp,cy*cp]])
        original=np.load(reference/f'depth-{yaw}-{pitch}.npz')
        for component,(v,t,volume) in enumerate(meshes):
            points=(v-ref['center'])@rotation.T;points[:,:2]*=[ref['scale'],-ref['scale']];points[:,:2]+=size/2
            near=np.full((size,size),np.inf);far=np.full((size,size),-np.inf)
            nearest_id=np.full((size,size),-1,dtype=int);farthest_id=nearest_id.copy()
            for identity,tri in enumerate(points[t]):
                a,b,c=tri;den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
                # Positive signed screen area: outward source normal points toward -Z.
                if den<=1e-12:continue
                lo=np.maximum(0,np.ceil(tri[:,:2].min(axis=0)-.5).astype(int));hi=np.minimum(size-1,np.floor(tri[:,:2].max(axis=0)-.5).astype(int))
                if np.any(lo>hi):continue
                xx=np.arange(lo[0],hi[0]+1)[None,:]+.5;yy=np.arange(lo[1],hi[1]+1)[:,None]+.5
                u=((b[1]-c[1])*(xx-c[0])+(c[0]-b[0])*(yy-c[1]))/den
                w=((c[1]-a[1])*(xx-c[0])+(a[0]-c[0])*(yy-c[1]))/den;h=1-u-w
                inside=(u>=-1e-10)&(w>=-1e-10)&(h>=-1e-10);z=u*a[2]+w*b[2]+h*c[2]
                region=np.s_[lo[1]:hi[1]+1,lo[0]:hi[0]+1];n=near[region];f=far[region]
                closer=inside&(z<n);farther=inside&(z>f)
                nearest_id[region][closer]=identity;farthest_id[region][farther]=identity;n[closer]=z[closer];f[farther]=z[farther]
            visible=np.isfinite(near);multiple=visible&((far-near)>1e-5)
            target=original[f'component{component}'];both=visible&np.isfinite(target)
            locations=np.argwhere(multiple);witnesses=[]
            for row,col in locations[::max(1,len(locations)//4)][:4]:
                witnesses.append(dict(pixel=[int(col),int(row)],nearestTriangle=int(nearest_id[row,col]),farthestTriangle=int(farthest_id[row,col]),depths=[float(near[row,col]),float(far[row,col])]))
            row=dict(component=component,yaw=yaw,pitch=pitch,sourceSignedVolume=volume,visiblePixels=int(visible.sum()),multipleFrontLayerPixels=int(multiple.sum()),
                frontCullingCoverageDifference=int(np.count_nonzero(visible^np.isfinite(target))),
                nearestFrontVsTwoSidedDepthMax=float(np.abs(near[both]-target[both]).max()),
                maxFrontLayerDepthGap=float((far[multiple]-near[multiple]).max()) if multiple.any() else 0,witnesses=witnesses)
            rows.append(row);print(json.dumps(row),flush=True)
            labels=np.zeros((size,size,3),dtype=np.uint8);labels[visible]=[100,100,100];labels[multiple]=[255,100,0]
            Image.fromarray(labels).save(out/f'self-overlap-{component}-{yaw}-{pitch}.png')
    (out/'report.json').write_text(json.dumps(dict(status='SOURCE_SELF_OCCLUSION_MEASUREMENTS_NOT_NATIVE',sourceSha256=SHA,
        camera=ref,minimumDistinctLayerGap=1e-5,rows=rows,hostExecuted=False),indent=2)+'\n')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--reference',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();run(a.source,a.reference,a.out)
