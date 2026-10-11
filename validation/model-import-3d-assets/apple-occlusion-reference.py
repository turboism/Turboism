#!/usr/bin/env python3
"""Per-triangle depth reference for the authorized high-resolution apple.

Diagnostic ownership colors are not materials or a Cubism render. No simplification,
backface culling, or average-depth sorting is used. Each component is rasterized
independently to measure visibility swaps that one whole-component order cannot express.
"""
import argparse,hashlib,json,struct,time
from pathlib import Path
import numpy as np
from PIL import Image

def raster_depth(points,triangles,size):
    depth=np.full((size,size),np.inf)
    for tri in points[triangles]:
        a,b,c=tri;den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
        if abs(den)<1e-12:continue
        lo=np.maximum(0,np.ceil(tri[:,:2].min(axis=0)-.5).astype(int));hi=np.minimum(size-1,np.floor(tri[:,:2].max(axis=0)-.5).astype(int))
        if np.any(lo>hi):continue
        xx=np.arange(lo[0],hi[0]+1)[None,:]+.5;yy=np.arange(lo[1],hi[1]+1)[:,None]+.5
        u=((b[1]-c[1])*(xx-c[0])+(c[0]-b[0])*(yy-c[1]))/den
        v=((c[1]-a[1])*(xx-c[0])+(a[0]-c[0])*(yy-c[1]))/den;w=1-u-v
        inside=(u>=-1e-10)&(v>=-1e-10)&(w>=-1e-10)
        z=u*a[2]+v*b[2]+w*c[2];tile=depth[lo[1]:hi[1]+1,lo[0]:hi[0]+1]
        np.minimum(tile,np.where(inside,z,np.inf),out=tile)
    return depth

def run(source,out,poses=None):
    raw=source.read_bytes();magic,version,total=struct.unpack_from('<III',raw)
    assert (magic,version,total)==(0x46546c67,2,len(raw))
    length,kind=struct.unpack_from('<II',raw,12);assert kind==0x4e4f534a
    doc=json.loads(raw[20:20+length]);binary=raw[28+length:];meshes=[]
    assert len(doc['meshes'])==2 and doc['nodes']==[{'mesh':i,'name':m['name']} for i,m in enumerate(doc['meshes'])]
    def data(index,dim,dtype):
        a=doc['accessors'][index];v=doc['bufferViews'][a['bufferView']]
        return np.frombuffer(binary,dtype=dtype,count=a['count']*dim,offset=v.get('byteOffset',0)+a.get('byteOffset',0)).reshape(-1,dim).copy()
    for mesh in doc['meshes']:
        p=mesh['primitives'][0];meshes.append((data(p['attributes']['POSITION'],3,'<f4').astype(float),data(p['indices'],1,'<u4').reshape(-1,3)))
    assert sum(len(t) for v,t in meshes)==150784
    allv=np.concatenate([v for v,t in meshes]);center=(allv.min(axis=0)+allv.max(axis=0))/2
    radius=np.linalg.norm(allv-center,axis=1).max();size=384;scale=160/radius
    out.mkdir(parents=True,exist_ok=False);results=[]
    for yaw,pitch in (poses if poses is not None else [(0,0),(-30,-30),(30,30)]):
        start=time.monotonic();y,p=np.radians([yaw,pitch]);cy,sy=np.cos(y),np.sin(y);cp,sp=np.cos(p),np.sin(p)
        rotation=np.array([[cy,sy*sp,sy*cp],[0,cp,-sp],[-sy,cy*sp,cy*cp]])
        depths=[]
        for vertices,triangles in meshes:
            points=(vertices-center)@rotation.T;points[:,:2]*=[scale,-scale];points[:,:2]+=size/2
            depth=raster_depth(points,triangles,size)
            depths.append(depth)
        first,second=depths;overlap=np.isfinite(first)&np.isfinite(second);a=overlap&(first<second-1e-7);b=overlap&(second<first-1e-7)
        visible=np.isfinite(np.minimum(first,second));owner=np.where(first<second,1,2);owner[~visible]=0
        # Labels only: black background, light gray component 0, dark gray component 1.
        Image.fromarray(np.choose(owner,[0,230,110]).astype('uint8')).save(out/f'ownership-{yaw}-{pitch}.png')
        np.savez_compressed(out/f'depth-{yaw}-{pitch}.npz',component0=first,component1=second)
        def witness(mask):
            coords=np.argwhere(mask)
            if not len(coords):return None
            row,col=coords[len(coords)//2];return dict(pixel=[int(col),int(row)],depths=[float(first[row,col]),float(second[row,col])])
        row=dict(yaw=yaw,pitch=pitch,overlapPixels=int(overlap.sum()),component0FrontPixels=int(a.sum()),component1FrontPixels=int(b.sum()),
            singleWholeComponentOrderInsufficient=bool(a.any() and b.any()),component0FrontWitness=witness(a),component1FrontWitness=witness(b),elapsedSeconds=time.monotonic()-start)
        results.append(row);print(json.dumps(row),flush=True)
    report=dict(status='SOURCE_DEPTH_REFERENCE_NOT_IMPORTED',sourceSha256=hashlib.sha256(raw).hexdigest(),resolution=[size,size],
        center=center.tolist(),scale=scale,projection='ORTHOGRAPHIC_RyRx_NEGATIVE_SCREEN_Y_NEAREST_MIN_Z',twoSidedDiagnostic=True,
        materials='NOT_EVALUATED_DIAGNOSTIC_LABELS_ONLY',triangles=150784,poses=results,hostExecuted=False)
    (out/'reference.json').write_text(json.dumps(report,indent=2)+'\n')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--grid25',action='store_true');a=p.parse_args();run(a.source,a.out,[(y,p) for y in (-30,-15,0,15,30) for p in (-30,-15,0,15,30)] if a.grid25 else None)
