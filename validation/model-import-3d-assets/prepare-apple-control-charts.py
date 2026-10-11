#!/usr/bin/env python3
"""Attribute every original apple triangle corner to a subdivision control chart.

Texture UV and source position/index remain separate from chart coordinates. This
is a sidecar for fitting; it does not replace the mesh or claim Cubism Warp fidelity.
"""
import argparse,hashlib,importlib.util,json,struct
from pathlib import Path
import numpy as np
OBJ_SHA='afbb79c4d618431ee505f1dbac4553401f36669b4a71b28336975768f73bca34'
GLB_SHA='54331e29af9a3ca07f11017712ce9156208a8ae8538b0a80b78e8c5eb8a894e0'
def load_module(name):
    s=importlib.util.spec_from_file_location(name,Path(__file__).with_name(name+'.py'));m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m

def quad_parameters(root_faces,levels):
    params=np.broadcast_to(np.array([[0,0],[1,0],[1,1],[0,1]],dtype=float),(root_faces,4,2)).copy()
    for _ in range(levels):
        mid=(params+np.roll(params,-1,axis=1))/2;center=np.broadcast_to(params.mean(axis=1)[:,None,:],params.shape)
        params=np.stack([params,mid,center,np.roll(mid,1,axis=1)],axis=2).reshape(-1,4,2)
    return params

def run(obj,glb,subdivision,out):
    raw=obj.read_bytes();assert hashlib.sha256(raw).hexdigest()==OBJ_SHA
    positions=[];texture=[];groups=[]
    for line in raw.decode().splitlines():
        fields=line.split()
        if not fields:continue
        if fields[0]=='v':positions.append(list(map(float,fields[1:])))
        elif fields[0]=='vt':texture.append(list(map(float,fields[1:3])))
        elif fields[0]=='g':groups.append([])
        elif fields[0]=='f':
            assert len(fields)==5;groups[-1].append([[int(x)-1 for x in p.split('/')] for p in fields[1:]])
    positions=np.array(positions);texture=np.array(texture);assert len(groups)==2
    binary=glb.read_bytes();assert hashlib.sha256(binary).hexdigest()==GLB_SHA
    length=struct.unpack_from('<I',binary,12)[0];document=json.loads(binary[20:20+length]);blob=binary[28+length:]
    def data(index,dim,dtype):
        a=document['accessors'][index];v=document['bufferViews'][a['bufferView']]
        return np.frombuffer(blob,dtype,a['count']*dim,v.get('byteOffset',0)+a.get('byteOffset',0)).reshape(-1,dim)
    prior=json.loads((subdivision/'report.json').read_text());assert prior['status']=='SUBDIVISION_POSITION_MATCH'
    verifier=load_module('verify-apple-subdivision');out.mkdir(parents=True,exist_ok=False)
    chart_offset=0;components=[];inputs={str(obj):OBJ_SHA,str(glb):GLB_SHA}
    for component,levels in [(0,3),(1,5)]:
        root_path=subdivision/f'component-{component}-level-0.npz';high_path=subdivision/f'component-{component}-level-{levels}.npz'
        for p in [root_path,high_path,subdivision/'report.json']:inputs[str(p)]=hashlib.sha256(p.read_bytes()).hexdigest()
        root=np.load(root_path);v=root['vertices'];f=root['quads']
        for _ in range(levels):v,f=verifier.subdivide(v,f)
        high=np.load(high_path);assert np.array_equal(v,high['vertices']) and np.array_equal(f,high['quads'])
        original=np.array(groups[component],dtype=np.int64);source_ids=np.unique(original[:,:,0])
        fitted=np.array(prior['components'][component]['comparison']['fittedTranslationToObj'])
        match=verifier.compare(v+fitted,positions[source_ids],.001);assert match['bijective']
        mapped=source_ids[np.array(match['mapping'])];geometric=mapped[f]
        # Unique source face identity from its four original position indices.
        lookup={tuple(sorted(face)):i for i,face in enumerate(geometric)};assert len(lookup)==len(f)
        params=quad_parameters(len(root['quads']),levels);face_charts=np.arange(len(f))//(4**levels)+chart_offset
        source_charts=[];source_params=[]
        for corners in original:
            identity=lookup[tuple(sorted(corners[:,0]))];parameter_by_vertex=dict(zip(geometric[identity],params[identity]))
            source_charts.append(face_charts[identity]);source_params.append([parameter_by_vertex[int(c)] for c in corners[:,0]])
        source_params=np.array(source_params);source_charts=np.array(source_charts)
        # Verify chart continuity by source vertex identity within each chart, not texture UV equality.
        chart_vertices={}
        for chart,face,uv in zip(source_charts,original,source_params):
            for corner,p in zip(face,uv):
                key=(int(chart),int(corner[0]));value=tuple(p)
                if key in chart_vertices:assert chart_vertices[key]==value
                else:chart_vertices[key]=value
        tri_original=original[:,[0,1,2,0,2,3],:].reshape(-1,3,2)
        tri_params=source_params[:,[0,1,2,0,2,3],:].reshape(-1,3,2);tri_charts=np.repeat(source_charts,2)
        primitive=document['meshes'][component]['primitives'][0];gv=data(primitive['attributes']['POSITION'],3,'<f4');guv=data(primitive['attributes']['TEXCOORD_0'],2,'<f4')
        indices=data(primitive['indices'],1,'<u4').reshape(-1,3)
        assert np.array_equal(gv[indices],positions[tri_original[:,:,0]].astype('<f4'))
        assert np.array_equal(guv[indices],texture[tri_original[:,:,1]].astype('<f4'))
        # Provisional regular atlas coordinates, distinct from original texture UV.
        bases=np.stack([4*(tri_charts%14),4*(tri_charts//14)],axis=1)
        atlas=bases[:,None,:]+3*tri_params
        np.savez_compressed(out/f'component-{component}.npz',sourceVertex=tri_original[:,:,0],sourceUv=tri_original[:,:,1],
            glbSlot=indices,chart=tri_charts,parameter=tri_params,unfold=atlas,source3D=gv[indices],textureUV=guv[indices])
        components.append(dict(component=component,charts=len(root['quads']),triangles=len(indices),sourcePositionAndUvCornersExact=True,
            uniqueSourceVertices=len(source_ids),uniqueChartVertices=len(chart_vertices),chartOffset=chart_offset,
            controlCageReconstructionMaxError=match['maximumMatchedDistance']))
        chart_offset+=len(root['quads'])
    report=dict(status='SOURCE_CONTROL_CHART_ATTRIBUTION_ONLY_NOT_NATIVE',inputsSha256=inputs,components=components,totalCharts=chart_offset,
        proposedAtlas=dict(chartColumns=14,chartRows=7,activeCellsPerAxis=3,gapCells=1,warpColumns=56,warpRows=28,gridPoints=57*29,
            at49x49KeysControlSamples=57*29*2401),
        sourceTrianglesUnchanged=True,textureUVUnchanged=True,sourceCoordinatesUnchanged=True,
        nativeWarpBasisVerified=False,controlFitVerified=False,hostExecuted=False)
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--obj',type=Path,required=True);p.add_argument('--glb',type=Path,required=True);p.add_argument('--subdivision',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();run(a.obj,a.glb,a.subdivision,a.out)
