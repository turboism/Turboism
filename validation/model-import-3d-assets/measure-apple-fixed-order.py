#!/usr/bin/env python3
"""Measure a single fixed triangle index order against frozen source depth images.

Centroid order is only a candidate, never an occlusion proof. Original triangles,
positions and component provenance are unchanged. Coverage, nearest depth and
component ownership are measured without excluding image boundaries.
"""
import argparse,hashlib,json,struct
from pathlib import Path
import numpy as np
from PIL import Image
SHA='54331e29af9a3ca07f11017712ce9156208a8ae8538b0a80b78e8c5eb8a894e0'
def run(source,reference,out,input_order=None):
    raw=source.read_bytes();assert hashlib.sha256(raw).hexdigest()==SHA
    length=struct.unpack_from('<I',raw,12)[0];doc=json.loads(raw[20:20+length]);binary=raw[28+length:]
    ref=json.loads((reference/'reference.json').read_text());assert ref['sourceSha256']==SHA
    def accessor(i,dim,dtype):
        a=doc['accessors'][i];v=doc['bufferViews'][a['bufferView']]
        return np.frombuffer(binary,dtype,a['count']*dim,v.get('byteOffset',0)+a.get('byteOffset',0)).reshape(-1,dim)
    meshes=[];components=[]
    for component,m in enumerate(doc['meshes']):
        p=m['primitives'][0];v=accessor(p['attributes']['POSITION'],3,'<f4').astype(float);ix=accessor(p['indices'],1,'<u4').reshape(-1,3)
        meshes.append(v[ix]);components.extend([component]*len(ix))
    triangles=np.concatenate(meshes);components=np.array(components);assert len(triangles)==150784
    order=np.argsort(-triangles[:,:,2].mean(axis=1),kind='stable') if input_order is None else np.fromfile(input_order,dtype='<i4')
    assert np.array_equal(np.sort(order),np.arange(len(triangles))), 'input order must preserve every triangle exactly once'
    out.mkdir(parents=True,exist_ok=False);np.save(out/'triangle-order.npy',order);rows=[];size=ref['resolution'][0]
    for pose in ref['poses']:
        yaw,pitch=pose['yaw'],pose['pitch'];y,p=np.radians([yaw,pitch]);cy,sy=np.cos(y),np.sin(y);cp,sp=np.cos(p),np.sin(p)
        rotation=np.array([[cy,sy*sp,sy*cp],[0,cp,-sp],[-sy,cy*sp,cy*cp]])
        points=(triangles-ref['center'])@rotation.T;points[:,:,:2]*=[ref['scale'],-ref['scale']];points[:,:,:2]+=size/2
        near=np.full((size,size),np.inf);painter=near.copy();nearest_id=np.full((size,size),-1,dtype=np.int32);painter_id=nearest_id.copy()
        for identity in order:
            tri=points[identity];a,b,c=tri;den=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
            if den<=1e-12:continue
            lo=np.maximum(0,np.ceil(tri[:,:2].min(axis=0)-.5).astype(int));hi=np.minimum(size-1,np.floor(tri[:,:2].max(axis=0)-.5).astype(int))
            if np.any(lo>hi):continue
            xx=np.arange(lo[0],hi[0]+1)[None,:]+.5;yy=np.arange(lo[1],hi[1]+1)[:,None]+.5
            u=((b[1]-c[1])*(xx-c[0])+(c[0]-b[0])*(yy-c[1]))/den
            v=((c[1]-a[1])*(xx-c[0])+(a[0]-c[0])*(yy-c[1]))/den;w=1-u-v
            inside=(u>=-1e-10)&(v>=-1e-10)&(w>=-1e-10);z=u*a[2]+v*b[2]+w*c[2]
            region=np.s_[lo[1]:hi[1]+1,lo[0]:hi[0]+1];tile=near[region];closer=inside&(z<tile)
            tile[closer]=z[closer];nearest_id[region][closer]=identity
            # Actual painter writes every covered pixel unconditionally, with no depth test.
            painter[region][inside]=z[inside];painter_id[region][inside]=identity
        depths=np.load(reference/f'depth-{yaw}-{pitch}.npz');original=np.minimum(depths['component0'],depths['component1'])
        assert np.array_equal(np.isfinite(near),np.isfinite(original))
        visible=np.isfinite(original);reference_error=float(np.abs(near[visible]-original[visible]).max());assert reference_error<1e-10
        gap=np.zeros((size,size));gap[visible]=painter[visible]-original[visible];bad=visible&(gap>1e-5)
        expected_owner=np.where(depths['component0']<depths['component1'],0,1)
        actual_owner=np.where(painter_id>=0,components[np.maximum(painter_id,0)],-1)
        both=np.isfinite(depths['component0'])&np.isfinite(depths['component1'])
        depth_difference=np.zeros((size,size));depth_difference[both]=np.abs(depths['component0'][both]-depths['component1'][both])
        untied=visible&(~both|(depth_difference>1e-5));owner_bad=untied&(actual_owner!=expected_owner)
        locations=np.argwhere(bad);witnesses=[]
        for row,col in locations[::max(1,len(locations)//4)][:4]:
            witnesses.append(dict(pixel=[int(col),int(row)],nearestTriangle=int(nearest_id[row,col]),paintedTriangle=int(painter_id[row,col]),
                components=[int(components[nearest_id[row,col]]),int(components[painter_id[row,col]])],depths=[float(original[row,col]),float(painter[row,col])]))
        result=dict(yaw=yaw,pitch=pitch,visiblePixels=int(visible.sum()),wrongDepthPixels=int(bad.sum()),wrongComponentPixels=int(owner_bad.sum()),
            maximumWrongDepthSourceUnits=float(gap[bad].max()) if bad.any() else 0,nearestDepthReferenceMaxError=reference_error,witnesses=witnesses)
        rows.append(result);print(json.dumps(result),flush=True)
        np.savez_compressed(out/f'pose-{yaw}-{pitch}.npz',nearestDepth=near,painterDepth=painter,nearestTriangle=nearest_id,painterTriangle=painter_id)
        image=np.zeros((size,size,3),dtype=np.uint8);image[visible]=[100,100,100];image[bad]=[255,100,0];image[owner_bad]=[0,220,255]
        Image.fromarray(image).save(out/f'wrong-order-{yaw}-{pitch}.png')
    report=dict(status='FIXED_ORDER_DIAGNOSTIC_NOT_IMPORT',sourceSha256=SHA,orderSha256=hashlib.sha256((out/'triangle-order.npy').read_bytes()).hexdigest(),
        candidate='One immutable source-centroid-Z descending triangle sequence; front-facing culling',inputOrderSha256=hashlib.sha256(input_order.read_bytes()).hexdigest() if input_order else None,camera=ref,rows=rows,
        mismatchGapSourceUnits=1e-5,boundaryMask=False,hostExecuted=False,continuousDomainProved=False)
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--reference',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--order',type=Path);a=p.parse_args();run(a.source,a.reference,a.out,a.order)
