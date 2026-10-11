#!/usr/bin/env python3
"""Test pinned FBX cages against OBJ positions. Diagnostic, not native import.

Closed manifold Catmull-Clark only; no creases, UV or material reconstruction.
Matching is bijective within a declared source-coordinate tolerance.
"""
import argparse, hashlib, importlib.util, itertools, json
from pathlib import Path
import numpy as np


def module(name):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(name+'.py'))
    value=importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value


def subdivide(v,f):
    n=len(v); face=v[f].mean(axis=1)
    pairs=np.stack([f,np.roll(f,-1,axis=1)],axis=-1)
    edges,inverse,counts=np.unique(np.sort(pairs.reshape(-1,2),axis=1),axis=0,return_inverse=True,return_counts=True)
    if not np.all(counts==2):raise ValueError('closed two-manifold required')
    edge_faces=np.zeros((len(edges),3));np.add.at(edge_faces,inverse,np.repeat(face,4,axis=0))
    mid=v[edges].mean(axis=1);edge=(2*mid+edge_faces)/4
    valence=np.bincount(edges.ravel(),minlength=n);fs=np.zeros_like(v);es=np.zeros_like(v)
    np.add.at(fs,f.ravel(),np.repeat(face,4,axis=0));np.add.at(es,edges.ravel(),np.repeat(mid,2,axis=0))
    if np.any(valence<3):raise ValueError('invalid valence')
    updated=(fs/valence[:,None]+2*es/valence[:,None]+(valence[:,None]-3)*v)/valence[:,None]
    ids=inverse.reshape(-1,4)+n;fid=np.arange(len(f))+n+len(edges)
    quads=np.stack([f,ids,np.broadcast_to(fid[:,None],f.shape),np.roll(ids,1,axis=1)],axis=-1).reshape(-1,4)
    return np.concatenate([updated,edge,face]),quads


def compare(actual,expected,tolerance):
    buckets={}
    for i,p in enumerate(expected):buckets.setdefault(tuple(np.floor(p/tolerance).astype(int)),[]).append(i)
    used=set();errors=[];unmatched=[];mapping=[]
    for i,p in enumerate(actual):
        cell=np.floor(p/tolerance).astype(int);candidates=[]
        for d in itertools.product((-1,0,1),repeat=3):candidates.extend(buckets.get(tuple(cell+d),[]))
        candidates=[j for j in candidates if j not in used]
        if candidates:
            distances=np.linalg.norm(expected[candidates]-p,axis=1);k=int(np.argmin(distances))
            if distances[k]<=tolerance:used.add(candidates[k]);errors.append(float(distances[k]));mapping.append(candidates[k]);continue
        unmatched.append(i);mapping.append(-1)
    return dict(mapping=mapping,matched=len(used),unmatchedActual=len(unmatched),unmatchedExpected=len(expected)-len(used),
        maximumMatchedDistance=max(errors,default=None),bijective=len(used)==len(actual)==len(expected),
        actualBounds=[actual.min(axis=0).tolist(),actual.max(axis=0).tolist()],expectedBounds=[expected.min(axis=0).tolist(),expected.max(axis=0).tolist()])


def run(source,out):
    fbx=module('apple-fbx');nodes=fbx.read(source/'apple.fbx');flat=list(fbx.walk(nodes))
    objects={n['props'][0]:n for n in flat if n['name'] in ('Model','Geometry')}
    parents={n['props'][1]:n['props'][2] for n in flat if n['name']=='C' and n['props'][0]=='OO'}
    raw=(source/'apple.obj').read_bytes();assert hashlib.sha256(raw).hexdigest()==module('convert-apple-obj').SOURCE_SHA
    vertices=[];groups=[];obj_faces=[]
    for line in raw.decode().splitlines():
        p=line.split()
        if not p:continue
        if p[0]=='v':vertices.append(list(map(float,p[1:])))
        elif p[0]=='g':groups.append(set());obj_faces.append([])
        elif p[0]=='f':
            face=[int(c.split('/')[0])-1 for c in p[1:]];groups[-1].update(face);obj_faces[-1].append(face)
    vertices=np.array(vertices);results=[];out.mkdir(parents=True,exist_ok=False)
    for group,(identity,levels) in enumerate([(2169480610272,3),(2169480607200,5)]):
        geo=objects[identity];children={n['name']:n['props'] for n in geo['children']}
        v=np.array(children['Vertices'][0]).reshape(-1,3);faces=[];face=[]
        for index in children['PolygonVertexIndex'][0]:
            face.append(index if index>=0 else -index-1)
            if index<0:assert len(face)==4;faces.append(face);face=[]
        assert not face
        translation=np.zeros(3);parent=parents[identity]
        while parent:
            props={n['props'][0]:n['props'][4:] for n in fbx.walk(objects[parent]['children']) if n['name']=='P'}
            for name,default in [('Lcl Rotation',[0,0,0]),('Lcl Scaling',[1,1,1]),('PreRotation',[0,0,0]),('PostRotation',[0,0,0]),('GeometricTranslation',[0,0,0]),('GeometricRotation',[0,0,0]),('GeometricScaling',[1,1,1])]:
                assert props.get(name,default)==default,(name,props.get(name))
            translation+=props.get('Lcl Translation',[0,0,0]);parent=parents[parent]
        v+=translation;f=np.array(faces);counts=[]
        for level in range(levels+1):
            np.savez_compressed(out/f'component-{group}-level-{level}.npz',vertices=v,quads=f)
            counts.append(dict(level=level,vertices=len(v),quads=len(f)))
            if level<levels:v,f=subdivide(v,f)
        expected=vertices[sorted(groups[group])]
        # Exporters used different scene origins. Record registration, never hide it.
        offset=(expected.min(axis=0)+expected.max(axis=0)-v.min(axis=0)-v.max(axis=0))/2
        result=compare(v+offset,expected,0.001)
        result['fittedTranslationToObj']=offset.tolist()
        mapping=np.array(result.pop('mapping'));source_ids=np.array(sorted(groups[group]))
        def canonical(face):
            q=tuple(map(int,face));rev=q[::-1]
            return min(*(q[i:]+q[:i] for i in range(4)),*(rev[i:]+rev[:i] for i in range(4)))
        result['quadConnectivityMatchesIgnoringWinding']=bool(result['bijective'] and
            sorted(canonical(q) for q in source_ids[mapping[f]])==sorted(canonical(q) for q in obj_faces[group]))
        results.append(dict(component=group,levels=counts,translation=translation.tolist(),comparison=result))
    report=dict(status='SUBDIVISION_POSITION_MATCH' if all(r['comparison']['bijective'] for r in results) else 'SUBDIVISION_POSITION_MISMATCH',
        toleranceSourceUnits=0.001,sourceFbxSha256=fbx.SHA,sourceObjSha256=hashlib.sha256(raw).hexdigest(),components=results,
        UVCorrespondenceVerified=False,hostExecuted=False)
    (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();run(a.source,a.out)
