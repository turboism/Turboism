"""Independent XYZ projection from the previously source-audited apple chart slots.

Does not fit captured coordinates; raster visibility is outside this geometry gate.
"""
import argparse,hashlib,json,math,struct
from pathlib import Path
import numpy as np
import runpy
HERE=Path(__file__).resolve().parent
O=runpy.run_path(str(HERE/'verify-xyz-content.py'))
POSES=O['POSES'];require=O['require']
SOURCE_SHA='3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999'
CENTER=np.array([0,130.6125549674034,0]);SCALE=2.954462184876577

def source_charts(path):
    raw=path.read_bytes();at=0
    def read(dtype,n,shape=None):
        nonlocal at
        a=np.frombuffer(raw,dtype,count=n,offset=at);at+=a.nbytes
        return a.reshape(shape) if shape is not None else a
    columns,rows,count=map(int,read('>i4',3));require(count==98,'audited apple chart bank')
    charts=[]
    for i in range(count):
        n,t=map(int,read('>i4',2));xyz=read('>f4',n*3,(n,3));uv=read('>f4',n*2,(n,2));read('>f4',n*2)
        identities=read('>i4',n*3,(n,3));indices=read('>i4',t*3);read('>i4',t*2)
        require(np.all(identities[:,2]==i),'source chart identity')
        image=np.column_stack((identities[:,0]*512+1+256*uv[:,0].astype(float),1+128*uv[:,1].astype(float))).astype('>f4')
        shader=(image.astype(float)/np.array([1024,256])).astype('>f4')
        fingerprint=hashlib.sha256(image.tobytes()+shader.tobytes()+indices.tobytes()).hexdigest()
        charts.append(dict(xyz=xyz.astype(float),count=n,triangles=t,geometrySha256=fingerprint))
    require(sum(c['triangles'] for c in charts)==150784,'complete source triangle count')
    return charts

def verify(report,charts,artifact_reader):
    require(report['status']=='PASS' and report['schemaVersion'] in ('mi3d-apple-xyz360-v1','mi3d-apple-xyz360-reopen-v1'),'native apple XYZ capture')
    require(report['inputSha256']==SOURCE_SHA and report['framesCaptured'] is True,'authorized source/capture')
    imported=report['imported'];axes=report['axes']
    require(set(axes)=={'X','Y','Z'} and len(set(axes.values()))==3,'three independent native rotation axes')
    parameters={p[0]:p[1:] for p in imported['parameters']}
    require(all(parameters.get(axis)==[-180,0,180,0] for axis in axes.values()),'full XYZ domains and restored neutral')
    warps=imported['warps'];require(len(warps)==1 and warps[0]['formCount']==2401,'full ordinary Warp bank')
    require(len(warps[0]['axes'])==2 and {a['id'] for a in warps[0]['axes']}=={axes['X'],axes['Y']}
        and all(a['keys']==[-180+7.5*i for i in range(49)] for a in warps[0]['axes']),'dense X/Y geometry keys')
    O['rotation_inventory'](report)
    meshes=imported['meshes'];require(len(meshes)==len(charts)==98,'complete native apple chart inventory')
    for mesh,chart in zip(meshes,charts):
        require(mesh['vertexCount']==chart['count'] and mesh['triangleCount']==chart['triangles'] and mesh['geometrySha256']==chart['geometrySha256'],'original apple source slots/UV/indices')
        require(mesh['formCount']==mesh['cellCount']==49 and len(mesh['axes'])==2
            and {a['id'] for a in mesh['axes']}=={axes['X'],axes['Y']}
            and all(a['keys']==[-180+60*i for i in range(7)] for a in mesh['axes']),'bounded layer forms preserve full axis domains')
    frames=report['frames'];require(len(frames)==12,'complete XYZ pose bank');results=[]
    for i,(frame,pose) in enumerate(zip(frames,POSES)):
        require(frame['poseXYZ']==list(pose) and len(frame['evaluated'])==98,'pose/mesh identity')
        require(frame['waitReason'] in ('CONSUMED','ALREADY_CONSUMED') and len(frame['lastObservation'])==98,'complete native consumption witness')
        wanted={p[0]:p[4] for p in imported['parameters']}
        wanted.update({axes[axis]:value for axis,value in zip(('X','Y','Z'),pose)})
        for observation,mesh in zip(frame['lastObservation'],meshes):
            require(observation['artMeshId']==mesh['id'] and observation['sourceGuid']==mesh['sourceGuid'],'consumed source binding')
            for state in ('live','consumed'):
                values=observation[state]['values']
                require(len(values)==len(wanted) and len({v[0] for v in values})==len(values)
                    and len({v[1] for v in values})==len(values) and {v[1]:v[2] for v in values}==wanted,'live/consumed XYZ parameters')
        require(frame['artifact']==f'xyz-frames/{i}.png','native frame path')
        raw=artifact_reader(frame['artifact'])
        require(0<len(raw)<=8*1024*1024 and hashlib.sha256(raw).hexdigest()==frame['sha256'],'native PNG identity and budget')
        matrix=np.array([O['transform'](v,pose) for v in np.eye(3)]).T
        maximum=0
        for observation,mesh,chart in zip(frame['evaluated'],meshes,charts):
            require(observation['id']==mesh['id'],'native source identity/order')
            expected=(chart['xyz']-CENTER)@matrix.T
            expected=np.column_stack((512+SCALE*expected[:,0],512-SCALE*expected[:,1]))
            actual=np.array(observation['positions'],dtype=float)
            require(actual.shape==expected.shape and np.all(np.isfinite(actual)),'complete finite source vertex projection')
            maximum=max(maximum,float(np.max(np.linalg.norm(actual-expected,axis=1))))
        require(maximum<=5,'apple full-source world error exceeds 5px')
        results.append(dict(poseXYZ=list(pose),maxErrorPixels=maximum))
    for a,b in ((1,2),(3,4),(5,6)):
        require(max(O['G']['error'](x['positions'],y['positions']) for x,y in zip(frames[a]['evaluated'],frames[b]['evaluated']))<=.001,'apple +/-180 native seam')
    return dict(status='APPLE_XYZ_FULL_SOURCE_GEOMETRY_CHECKED_VISIBILITY_NOT_ADMITTED',triangles=150784,charts=98,frames=results)

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--report',type=Path,required=True);p.add_argument('--packed',type=Path,required=True);p.add_argument('--source-audit',type=Path,required=True);p.add_argument('--manifest',type=Path,required=True);args=p.parse_args()
    audit=json.loads(args.source_audit.read_text());require(audit['sourceDataExact'] is True and audit['triangles']==150784,'prior independent source audit')
    manifest=json.loads(args.manifest.read_text());require(manifest['independent-source-report.json']==hashlib.sha256(args.source_audit.read_bytes()).hexdigest(),'frozen independent source audit');entry=manifest.get('prepared/packed.bin') or manifest.get(str(args.packed))
    if isinstance(entry,dict):entry=entry.get('sha256')
    require(entry==hashlib.sha256(args.packed.read_bytes()).hexdigest(),'frozen source fixture identity')
    print(json.dumps(verify(json.loads(args.report.read_text()),source_charts(args.packed),
        lambda relative:(args.report.parent/relative).read_bytes()),indent=2))
