#!/usr/bin/env python3
"""Independent sampled Cat surface-distance check. Not a native/raster acceptance.

Reads original GLB accessors itself and tests both directions using point-to-
triangle distance, independently of Java collapse correspondences and normals.
The local overlay bound is separate evidence; sampling cannot prove Hausdorff
accuracy everywhere or texture/occlusion equivalence.
"""
import argparse
import hashlib
import json
from pathlib import Path
import struct
import numpy as np

SOURCE_SHA='4d0688298a366bfd5501918a7afac5dfd4f6aa35445a4f73077435045ba02ee7'

def require(ok,message):
    if not ok:raise ValueError('CAT_SIMPLIFICATION: '+message)

def original(path):
    raw=path.read_bytes();require(hashlib.sha256(raw).hexdigest()==SOURCE_SHA,'admitted Cat source SHA')
    magic,version,length,n,kind=struct.unpack_from('<5I',raw)
    require((magic,version,length,kind)==(0x46546c67,2,len(raw),0x4e4f534a),'GLB header')
    doc=json.loads(raw[20:20+n]);binary=raw[28+n:]
    require(doc['nodes']==[{'mesh':0,'name':'Cat'}] and len(doc['meshes'])==1,'fixed identity Cat instance')
    def accessor(index,width):
        a=doc['accessors'][index];v=doc['bufferViews'][a['bufferView']]
        require('sparse' not in a and not a.get('normalized',False),'plain input accessor')
        dtype=np.dtype({5126:'<f4',5125:'<u4',5123:'<u2',5121:'u1'}[a['componentType']])
        offset=v.get('byteOffset',0)+a.get('byteOffset',0);stride=v.get('byteStride',width*dtype.itemsize)
        return np.ndarray((a['count'],width),dtype=dtype,buffer=binary,offset=offset,strides=(stride,dtype.itemsize)).astype(float)
    positions=[];uvs=[];triangles=[];offset=0
    for p in doc['meshes'][0]['primitives']:
        xyz=accessor(p['attributes']['POSITION'],3);uv=accessor(p['attributes']['TEXCOORD_0'],2);idx=accessor(p['indices'],1).astype(int).reshape(-1,3)
        positions.append(xyz);uvs.append(uv);triangles.append(idx+offset);offset+=len(xyz)
    return np.concatenate(positions),np.concatenate(uvs),np.concatenate(triangles)

class DistanceOracle:
    def __init__(self,vertices,indices):
        self.triangles=vertices[indices];self.low=self.triangles.min(axis=1);self.high=self.triangles.max(axis=1)
    def within(self,point,limit):
        gap=np.maximum(np.maximum(self.low-point,point-self.high),0)
        candidates=np.flatnonzero(np.einsum('ij,ij->i',gap,gap)<=limit*limit)
        if len(candidates)==0:return float('inf')
        t=self.triangles[candidates];a,b,c=t[:,0],t[:,1],t[:,2];ab=b-a;ac=c-a;ap=point-a
        normal=np.cross(ab,ac);normal2=np.einsum('ij,ij->i',normal,normal)
        d00=np.einsum('ij,ij->i',ab,ab);d01=np.einsum('ij,ij->i',ab,ac);d11=np.einsum('ij,ij->i',ac,ac)
        d20=np.einsum('ij,ij->i',ap,ab);d21=np.einsum('ij,ij->i',ap,ac);denominator=d00*d11-d01*d01
        good=denominator>0;v=np.zeros(len(t));w=v.copy()
        np.divide(d11*d20-d01*d21,denominator,out=v,where=good);np.divide(d00*d21-d01*d20,denominator,out=w,where=good)
        inside=good & (v>=0) & (w>=0) & (v+w<=1)
        signed=np.einsum('ij,ij->i',ap,normal);distance=np.full(len(t),float('inf'))
        np.divide(signed*signed,normal2,out=distance,where=inside & (normal2>0))
        for start,end in ((a,b),(b,c),(c,a)):
            edge=end-start;relative=point-start;n=np.einsum('ij,ij->i',edge,edge);fraction=np.zeros(len(t))
            np.divide(np.einsum('ij,ij->i',relative,edge),n,out=fraction,where=n>0);fraction=np.clip(fraction,0,1)
            delta=start+fraction[:,None]*edge-point;distance=np.minimum(distance,np.einsum('ij,ij->i',delta,delta))
        return float(np.sqrt(distance.min()))

def samples(vertices,indices):
    triangles=vertices[indices];result=[]
    for bank in (vertices,triangles.mean(axis=1),(triangles[:,0]+triangles[:,1])/2):
        result.append(bank[np.linspace(0,len(bank)-1,min(512,len(bank)),dtype=int)])
    return np.concatenate(result)

def verify(source,candidate):
    p,uv,idx=original(source);raw=candidate.read_bytes();data=json.loads(raw)
    require(data['sourceSha256']==SOURCE_SHA and data['sourceTriangles']==len(idx)==70576,'candidate source provenance')
    optimized=[];texcoords=[];indices=[];offset=0
    for mesh in data['surfaces']:
        q=np.asarray(mesh['positions'],float);v=np.asarray(mesh['uvs'],np.float32).astype(float);i=np.asarray(mesh['indices'],int).reshape(-1,3)
        require(q.shape[1:]==(3,) and v.shape==(len(q),2) and np.all(np.isfinite(q)) and np.all(np.isfinite(v)),'complete finite attributes')
        require(np.all(i>=0) and np.all(i<len(q)),'candidate index domain')
        optimized.append(q);texcoords.append(v);indices.append(i+offset);offset+=len(q)
    q=np.concatenate(optimized);v=np.concatenate(texcoords);new=np.concatenate(indices)
    require(len(new)==data['outputTriangles']<len(idx),'actual reduced triangle count')
    attributes={tuple(x) for x in np.column_stack((p,uv))}
    require(all(tuple(x) in attributes for x in np.column_stack((q,v))),'retained original endpoint positions and UV pairs')
    pivot=(p.min(axis=0)+p.max(axis=0))/2;scale=400/np.linalg.norm(p-pivot,axis=1).max()
    require(abs(scale-data['scale'])<1e-8,'fixed original camera scale')
    bound=data['geometryBoundPixels'];require(0<bound<5,'finite geometry budget')
    limit=(bound+1e-6)/scale;results=[]
    for label,vertices,faces,target,triangles in (('source-to-optimized',p,idx,q,new),('optimized-to-source',q,new,p,idx)):
        oracle=DistanceOracle(target,triangles);points=samples(vertices,faces);maximum=0
        for point in points:
            distance=oracle.within(point,limit);require(distance<=limit,label+' sample exceeds declared surface bound')
            maximum=max(maximum,distance*scale)
        results.append(dict(direction=label,samples=len(points),maxSampleDistancePixels=maximum))
    # A displaced geometry must fail the same oracle; no permissive fallback.
    corrupted=DistanceOracle(q+100,new)
    require(not np.isfinite(corrupted.within(p[0],limit)),'displacement negative control')
    return dict(status='SAMPLED_SURFACE_GEOMETRY_ONLY_NOT_NATIVE_OR_RASTER_ACCEPTANCE',sourceSha256=SOURCE_SHA,candidateSha256=hashlib.sha256(raw).hexdigest(),sourceTriangles=len(idx),optimizedTriangles=len(new),declaredGeometryBoundPixels=bound,directions=results,displacementNegativeControl=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--source',type=Path,required=True);parser.add_argument('--candidate',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);args=parser.parse_args()
    result=verify(args.source,args.candidate);args.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))
