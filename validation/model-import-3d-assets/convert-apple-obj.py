#!/usr/bin/env python3
"""Freeze the authorized high-resolution apple OBJ as GLB, without simplification.

This is an offline fixture converter, not a production importer or occlusion claim.
Only this source digest is accepted. OBJ numeric UVs and coordinate units are retained;
no material is inferred from scene screenshots. Quads use their 0-2 diagonal.
"""
import argparse,hashlib,json,math,struct
from pathlib import Path
SOURCE_SHA='afbb79c4d618431ee505f1dbac4553401f36669b4a71b28336975768f73bca34'

def convert(source,out):
    raw=source.read_bytes()
    if hashlib.sha256(raw).hexdigest()!=SOURCE_SHA:raise ValueError('wrong authorized OBJ')
    if out.exists():raise ValueError('new output directory required')
    vertices=[];uvs=[];groups=[];group=None
    for line in raw.decode('utf-8').splitlines():
        fields=line.split()
        if not fields or fields[0].startswith('#'):continue
        kind=fields[0]
        if kind in ('v','vt'):
            values=tuple(map(float,fields[1:]));dimension=3 if kind=='v' else 2
            if kind=='vt' and len(values)==3 and values[2]==0:values=values[:2]
            if len(values)!=dimension or not all(map(math.isfinite,values)):raise ValueError('invalid coordinate')
            (vertices if kind=='v' else uvs).append(values)
        elif kind=='g':
            group={'name':' '.join(fields[1:]),'faces':[]};groups.append(group)
        elif kind=='f':
            if group is None or len(fields)!=5:raise ValueError('expected grouped quads')
            corners=[]
            for field in fields[1:]:
                pair=field.split('/')
                if len(pair)!=2:raise ValueError('expected position/UV corner')
                a,b=map(int,pair)
                if not 1<=a<=len(vertices) or not 1<=b<=len(uvs):raise ValueError('corner out of range')
                corners.append((a-1,b-1))
            group['faces'].append(corners)
        else:raise ValueError('unsupported OBJ record '+kind)
    binary=bytearray();views=[];accessors=[];meshes=[];mapping=[]
    def accessor(values,kind,components):
        while len(binary)%4:binary.append(0)
        start=len(binary);fmt='f' if kind==5126 else 'I'
        for value in values:binary.extend(struct.pack('<'+fmt*components,*value))
        views.append(dict(buffer=0,byteOffset=start,byteLength=len(binary)-start))
        row=dict(bufferView=len(views)-1,componentType=kind,count=len(values),type={1:'SCALAR',2:'VEC2',3:'VEC3'}[components])
        if components==3:
            rounded=[struct.unpack_from('<fff',binary,start+i*12) for i in range(len(values))]
            row.update(min=[min(v[i] for v in rounded) for i in range(3)],max=[max(v[i] for v in rounded) for i in range(3)])
        accessors.append(row);return len(accessors)-1
    for group in groups:
        slots={};indices=[]
        for face in group['faces']:
            local=[slots.setdefault(pair,len(slots)) for pair in face]
            indices.extend((local[0],local[1],local[2],local[0],local[2],local[3]))
        pairs=list(slots);positions=[vertices[a] for a,b in pairs];texture=[uvs[b] for a,b in pairs]
        p=accessor(positions,5126,3);uv=accessor(texture,5126,2);ix=accessor([(i,) for i in indices],5125,1)
        meshes.append(dict(name=group['name'],primitives=[dict(attributes={'POSITION':p,'TEXCOORD_0':uv},indices=ix,mode=4)]))
        mapping.append(dict(name=group['name'],sourceFaces=len(group['faces']),triangles=len(indices)//3,slots=len(slots),sourceCorners=pairs))
    doc=dict(asset=dict(version='2.0',generator='Turboism authorized apple fixture converter'),scene=0,
        scenes=[dict(nodes=list(range(len(meshes))))],nodes=[dict(mesh=i,name=g['name']) for i,g in enumerate(groups)],
        meshes=meshes,buffers=[dict(byteLength=len(binary))],bufferViews=views,accessors=accessors)
    body=json.dumps(doc,separators=(',',':')).encode();body+=b' '*((-len(body))%4);binary+=b'\0'*((-len(binary))%4)
    glb=struct.pack('<III',0x46546c67,2,28+len(body)+len(binary))+struct.pack('<II',len(body),0x4e4f534a)+body+struct.pack('<II',len(binary),0x004e4942)+binary
    # Independently read every encoded position/UV back against its source corner.
    maximum=0.0
    for mesh,row in zip(meshes,mapping):
        attrs=mesh['primitives'][0]['attributes']
        for name,component,source_values,corner in [('POSITION',3,vertices,0),('TEXCOORD_0',2,uvs,1)]:
            a=accessors[attrs[name]];view=views[a['bufferView']]
            for i,pair in enumerate(row['sourceCorners']):
                read=struct.unpack_from('<'+'f'*component,binary,view['byteOffset']+i*component*4)
                expected=source_values[pair[corner]]
                if read!=struct.unpack('<'+'f'*component,struct.pack('<'+'f'*component,*expected)):raise ValueError('roundtrip differs')
                maximum=max(maximum,max(abs(a-b) for a,b in zip(read,expected)))
    out.mkdir(parents=True);(out/'apple-high.glb').write_bytes(glb)
    report=dict(status='CONVERTED_NOT_IMPORTED',sourceSha256=SOURCE_SHA,glbSha256=hashlib.sha256(glb).hexdigest(),
        sourceVertices=len(vertices),sourceUVs=len(uvs),groups=[{k:v for k,v in m.items() if k!='sourceCorners'} for m in mapping],
        sourceCoordinates='UNCHANGED_NUMERIC_UNITS',sourceUVsPolicy='UNCHANGED_NUMERIC_UV_NO_MATERIAL',materials='NOT_PRESENT_IN_OBJ',
        triangulation='QUAD_0_2_DIAGONAL',simplified=False,float32RoundtripExact=True,maxFloat32ComponentDeviation=maximum,hostExecuted=False)
    (out/'conversion.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();convert(a.source,a.out)
