"""Independent frozen cube XYZ content oracle. Host admission is separate.

Uses Rz * Ry * Rx, the declared 400px bounding-sphere camera, and source UVs.
Raster differences are diagnostics: interpolated geometry can move pixel edges.
No coordinate/image fitting and no colors inferred from native images.
"""
import hashlib
import json
import math
from pathlib import Path
import runpy
import struct

HERE = Path(__file__).resolve().parent
TEXTURE = runpy.run_path(str(HERE / 'verify-texture-pixels.py'))
G = TEXTURE['ENTRY']['G']
R = TEXTURE['RENDER']
POSES = ((0,0,0),(-180,0,0),(180,0,0),(0,-180,0),(0,180,0),
         (0,0,-180),(0,0,180),(90,0,0),(0,90,0),(0,0,90),
         (37.5,-112.5,45),(-150,75,-135))
SCALE = 400 / math.sqrt(3)
MAX_ERROR = 3.0  # fixed before native results; 49-key two-axis interpolation


def require(ok, message):
    if not ok:
        raise ValueError('XYZ_CONTENT: ' + message)


def rotation_inventory(report):
    imported=report['imported'];banks=imported['rotations'];outer=report.get('outerPlacement')
    if outer is None:
        require(len(banks)==1,'native Roll inventory')
        return
    require(len(banks)==2 and len({outer['id'],outer['rollId'],outer['warpId']})==3,'placement/rotation inventory')
    require(outer['parent']=='' and outer['rollParent']==outer['id'] and outer['warpParent']==outer['rollId']
        and imported['warps'][0]['id']==outer['warpId'],'outer placement parent chain')
    require(outer['identityForm']==[0,0,0,1,False,False],'neutral unbound placement')
    placement=[b for b in banks if b.startswith('RotationAuthoringSnapshot[deformerId=DeformerId[value='+outer['id']+']')]
    roll=[b for b in banks if b.startswith('RotationAuthoringSnapshot[deformerId=DeformerId[value='+outer['rollId']+']')]
    require(len(placement)==len(roll)==1,'explicit stored rotation identities')
    text=placement[0]
    require(', baseAngle=0.0, axes=[],' in text and text.count('Cell[formGuid=')==1
        and text.count('RotationDeformerForm[')==1
        and text.endswith('RotationDeformerForm[angle=0.0, originX=0.0, originY=0.0, scale=1.0, reflectedX=false, reflectedY=false]]]]'),
        'complete unbound identity placement bank')


def transform(vertex, pose):
    require(len(pose)==3 and all(type(v) in (int,float) and math.isfinite(v)
                               and -180<=v<=180 for v in pose), 'finite XYZ domain')
    x,y,z=vertex
    p,a,r=map(math.radians,pose)
    cp,sp,ca,sa,cr,sr=math.cos(p),math.sin(p),math.cos(a),math.sin(a),math.cos(r),math.sin(r)
    # Closed matrix, independently of Java's projection/control sampler.
    return (cr*ca*x+(cr*sa*sp-sr*cp)*y+(cr*sa*cp+sr*sp)*z,
            sr*ca*x+(sr*sa*sp+cr*cp)*y+(sr*sa*cp-cr*sp)*z,
            -sa*x+ca*sp*y+ca*cp*z)


def project(vertex, pose):
    x,y,z=transform(vertex,pose)
    return (512+SCALE*x,512-SCALE*y,z)


def warp_source(cube):
    # The Warp path emits each chart's original triangles in descending source
    # centroid depth, retaining stable ties. Derive slot/index identity from GLB,
    # never from captured screen coordinates.
    sources,patches=G['cube_source'](cube)
    result=[];reordered=[]
    for source,patch in zip(sources,patches):
        indices=patch['indices']
        triangles=sorted(range(2),key=lambda t:-sum(source[indices[t*3+c]][2] for c in range(3))/3)
        slots=list(dict.fromkeys(indices[t*3+c] for t in triangles for c in range(3)))
        result.append([source[i] for i in slots])
        reordered.append(dict(patch,uvs=[patch['uvs'][i] for i in slots],
            indices=[slots.index(indices[t*3+c]) for t in triangles for c in range(3)]))
    return result,reordered


def geometry(frames, meshes, cube):
    sources,_=warp_source(cube)
    require(len(meshes)==6 and len({m['id'] for m in meshes})==6, 'six unique source mesh identities')
    require(len(frames)==len(POSES), 'complete predeclared pose inventory')
    results=[]
    for frame,pose in zip(frames,POSES):
        require(frame['poseXYZ']==list(pose), 'pose order/identity')
        evaluated=frame['evaluated']
        require(len(evaluated)==6 and [m['id'] for m in evaluated]==[m['id'] for m in meshes], 'evaluated source identity/order')
        maximum=0
        for row,source in zip(evaluated,sources):
            expected=[list(project(v,pose)[:2]) for v in source]
            maximum=max(maximum,G['error'](row['positions'],expected))
        require(maximum<=MAX_ERROR, f'pose {pose} world projection error {maximum}px')
        results.append(dict(poseXYZ=list(pose),maxErrorPixels=maximum))
    # Complete native geometry, not just projected silhouette, must close.
    for a,b in ((1,2),(3,4),(5,6)):
        require(max(G['error'](x['positions'],y['positions']) for x,y in
                    zip(frames[a]['evaluated'],frames[b]['evaluated']))<=.001, 'native +/-180 seam')
    return results


def reference(cube, glb, pose):
    require(tuple(pose) in POSES, 'predeclared raster pose')
    image=TEXTURE['source_image'](cube,glb)
    sources,patches=G['cube_source'](cube)
    triangles=[]
    for face,(source,patch) in enumerate(zip(sources,patches)):
        points=[project(v,pose) for v in source]
        for offset in (0,3):
            indices=patch['indices'][offset:offset+3]
            a,b,c=[points[i] for i in indices]
            area=(b[0]-a[0])*(c[1]-a[1])-(b[1]-a[1])*(c[0]-a[0])
            uv=[patch['uvs'][i] for i in indices]
            triangles.append((face,a,b,c,area,uv))
    expected=[];coverage=[0]*6
    for iy in range(2,1024,4):
        for ix in range(2,1024,4):
            x,y=ix+.5,iy+.5;hits=[];edge=False
            for face,a,b,c,area,uv in triangles:
                if not (min(a[0],b[0],c[0])-1<=x<=max(a[0],b[0],c[0])+1 and
                        min(a[1],b[1],c[1])-1<=y<=max(a[1],b[1],c[1])+1):
                    continue
                if min(R['segment_distance'](x,y,a,b),R['segment_distance'](x,y,b,c),
                       R['segment_distance'](x,y,c,a))<=1:
                    edge=True
                if abs(area)<1e-10:
                    continue
                v=((x-a[0])*(c[1]-a[1])-(y-a[1])*(c[0]-a[0]))/area
                w=((b[0]-a[0])*(y-a[1])-(b[1]-a[1])*(x-a[0]))/area;u=1-v-w
                if min(u,v,w)>=0:
                    hits.append((u*a[2]+v*b[2]+w*c[2],face,
                                 tuple(u*uv[0][k]+v*uv[1][k]+w*uv[2][k] for k in (0,1))))
            if edge:
                continue
            if hits:
                _,face,uv=min(hits);coverage[face]+=1
                color=TEXTURE['linear'](image,*uv)
            else:
                color=(0,0,0,0)
            expected.append((ix,iy,color))
    require(sum(coverage)>1000, 'source raster interior coverage')
    return expected,coverage


def verify(report,cube,glb,artifact_reader):
    reopened=report['schemaVersion']=='mi3d-cube-xyz360-reopen-v1'
    require(report['status']=='PASS' and report['framesCaptured'] is True and
            ((reopened and report['scope']=='OBSERVE_REOPENED_XYZ_SEED_ONLY') or
             (report['schemaVersion']=='mi3d-cube-xyz360-v1' and report['undoRestored'] is True and report['redoRestored'] is True)),
            'complete native capture')
    require(report['inputSha256']==hashlib.sha256(Path(glb).read_bytes()).hexdigest(), 'textured source identity')
    TEXTURE['ENTRY']['textured_source'](cube,glb)
    imported=report['imported'];axes=report['axes']
    require(set(axes)=={'X','Y','Z'} and len(set(axes.values()))==3,'three independent axes')
    parameters={p[0]:p[1:] for p in imported['parameters']}
    require(all(parameters.get(axis)==[-180,0,180,0] for axis in axes.values()),'XYZ full domains/defaults/restored values')
    keys=[-180+7.5*i for i in range(49)]
    def xy_axes(bank):
        require(len(bank)==2 and {a['id'] for a in bank}=={axes['X'],axes['Y']}
                and all(a['keys']==keys for a in bank),'dense X/Y keys')
    warps=imported['warps']
    require(len(warps)==1 and warps[0]['formCount']==2401,'joint Warp inventory')
    xy_axes(warps[0]['axes'])
    rotation_inventory(report)
    _,patches=warp_source(cube)
    require(len(imported['meshes'])==6,'six source charts')
    for mesh,patch in zip(imported['meshes'],patches):
        xy_axes(mesh['axes'])
        require(mesh['vertexCount']==len(patch['uvs']) and mesh['triangleCount']==2
                and mesh['formCount']==mesh['cellCount']==2401,'source chart topology/forms')
        # Frozen source image uses a one-pixel apron in a 512x256 material tile.
        # The oracle derives its source-coordinate/UV/index fingerprint without Java.
        positions=[(1+256*u,1+128*v) for u,v in patch['uvs']]
        shader=[(x/512,y/256) for x,y in positions]
        raw=b''.join(struct.pack('>ff',*p) for p in positions+shader)
        raw+=b''.join(struct.pack('>i',i) for i in patch['indices'])
        require(hashlib.sha256(raw).hexdigest()==mesh['geometrySha256'],'original slots/indices/material UV source hash')
    frames=report['frames'];geometric=geometry(frames,report['imported']['meshes'],cube)
    pixels=[]
    for i,(frame,pose) in enumerate(zip(frames,POSES)):
        neutral_assertion=i==0 and frame['waitReason']=='ALREADY_CONSUMED' and 'lastObservation' not in frame
        if neutral_assertion:
            # This legacy save capture records the runtime equality assertion only.
            # Full native geometry is independently checked above; do not label
            # neutral consumption as independently witnessed.
            require(pose==(0,0,0),'only unchanged neutral may omit a new pass')
        else:
            already=i==0 and pose==(0,0,0) and frame['waitReason']=='ALREADY_CONSUMED'
            require(already or (frame['waitReason']=='CONSUMED' and type(frame['attempts']) is int
                    and frame['attempts']>0 and type(frame['elapsedMillis']) is int
                    and 0<=frame['elapsedMillis']<15000),'fresh or unchanged neutral consumption protocol')
            observations=frame['lastObservation']
            require(len(observations)==6,'complete mesh consumption')
            wanted={p[0]:p[4] for p in imported['parameters']}
            wanted.update({axes[axis]:value for axis,value in zip(('X','Y','Z'),pose)})
            for obs,mesh in zip(observations,imported['meshes']):
                require(obs['artMeshId']==mesh['id'] and obs['sourceGuid']==mesh['sourceGuid'], 'consumed source binding')
                for state in ('live','consumed'):
                    values=obs[state]['values']
                    require(len(values)==len(wanted) and len({v[0] for v in values})==len(values)
                            and len({v[1] for v in values})==len(values)
                            and {v[1]:v[2] for v in values}==wanted, 'live/consumed XYZ values')
        require(frame['artifact']==f'xyz-frames/{i}.png', 'artifact identity')
        raw=artifact_reader(frame['artifact'])
        require(0<len(raw)<=8*1024*1024 and hashlib.sha256(raw).hexdigest()==frame['sha256'], 'native PNG hash/budget')
        expected,coverage=reference(cube,glb,pose)
        diagnostic=TEXTURE['inspect'](R['PNG'](raw),expected)
        pixels.append(dict(diagnostic,poseXYZ=list(pose),coverage=coverage))
    return dict(status='GEOMETRY_CONTENT_CHECKED_RASTER_DIAGNOSTIC_NOT_HOST_ADMITTED',
                geometry=geometric,pixelDiagnostics=pixels,
                neutralConsumptionEvidence=('RUNTIME_EQUALITY_ASSERTION_ONLY' if frames[0]['waitReason']=='ALREADY_CONSUMED' and 'lastObservation' not in frames[0] else 'NATIVE_PARAMETER_SNAPSHOT'))


if __name__=='__main__':
    import argparse
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report',required=True,type=Path)
    parser.add_argument('--state',required=True,type=Path)
    parser.add_argument('--cube',required=True,type=Path)
    parser.add_argument('--textured',required=True,type=Path)
    args=parser.parse_args()
    root=args.state.resolve(strict=True)
    def artifact(name):
        path=(root/name).resolve(strict=True)
        require(path.is_relative_to(root) and not (root/name).is_symlink(),'artifact containment')
        return path.read_bytes()
    print(json.dumps(verify(json.loads(args.report.read_text()),args.cube,args.textured,artifact),indent=2))
