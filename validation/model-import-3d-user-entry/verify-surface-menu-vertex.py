#!/usr/bin/env python3
"""Optimized vertex-path plane/seam menu authoring oracle (post SurfaceVertexAuthoring pipeline).

The production plugin now routes SOURCE_MATERIAL imports through the XYZ 360°
vertex path (9-point ellipse X/Y axes, 5-point Z roll deformer). Stored keyform
positions are baked by the editable-vertex interpolation plan; this verifier
checks structure, source identity, bindings, history and frame protocol but
does NOT reimplement the ellipse interpolation math, so exact stored-position
and pixel oracles are marked NOT_VERIFIED.
"""
import hashlib,json,math,re,runpy,struct,zlib
from pathlib import Path

HERE=Path(__file__).resolve().parent
G=runpy.run_path(str(HERE.parent/'model-import-3d-host-probe/verify-cube-geometry.py'))
PIXELS=runpy.run_path(str(HERE.parent/'model-import-3d-host-probe/verify-planar-pixels.py'))
HASHES={'plane-menu':'8aa60f903cee58d123831d6b66315b0537bafffcf68a064bcb6a0b0132d1b997','seam-menu':'c9605e030d4f174501d8f98f798dcad3a5a3851254b347b1b80e882fee8484dd'}
POSES=[(y,p) for y in (-30.,0.,30.) for p in (-30.,0.,30.)]
AXES_KEYS=[-180.,-135.,-90.,-45.,0.,45.,90.,135.,180.]
TEXTURE=(512,256)

def require(ok,why):
    if not ok:raise ValueError('SURFACE_MENU_VERTEX: '+why)

def f32(v):return G['f32'](v)

def source_uvs(glb):
    raw=Path(glb).read_bytes();digest=hashlib.sha256(raw).hexdigest()
    require(digest in HASHES.values(),'exact declared source')
    positions,uv,indices,image=PIXELS['source'](raw)
    padded=[(f32((256*u+1)/512),f32((128*v+1)/256)) for u,v in uv]
    triples=[tuple(padded[i] for i in indices[k:k+3]) for k in range(0,len(indices),3)]
    return raw,padded,triples

def verify_ui(r,glb,run):
    raw,padded,triples=source_uvs(glb)
    require(r['schemaVersion']=='mi3d-surface-menu-v1' and r['scope']=='REAL_MENU_IMPORT_UNDO_REDO_SOURCE_RENDER' and r['runId']==run and r['status']=='PASS' and 'failure' not in r,'explicit surface menu scope')
    require(r['textureLayout']=='EDGE_PADDED_POT' and r['inputSha256']==hashlib.sha256(raw).hexdigest() and r['appearance']=='SOURCE_MATERIAL','source material identity')
    require(r['seedDocumentId']!=r['targetDocumentId'] and r['menuInvoked'] is True and r['realChooserApproved'] is True,'real menu and chooser')
    require(r.get('chooserTitle')=='选择 GLB、glTF、OBJ 或模型 ZIP','declared production chooser title')
    c=r['completionMessage'];require(c.startswith('导入完成') and 'X / Y / Z' in c,'vertex import completion')
    plan=r['confirmation']
    require(all(t in plan for t in ('个曲面网格','面 → 导入 ','9 点椭圆插值','Z 5 点','±180°','撤销')),'declared optimized vertex plan')
    base,s=r['baseline'],r['imported']
    require(r['undoRestored'] is True and r['afterUndo']==base and r['redoRestored'] is True and r['afterRedo']==s,'complete single Undo/Redo')
    h=r['historyBefore'];require(r['historyAfter']==r['historyAfterRedo']==h+1 and r['historyAfterUndo']==h,'one native import history entry')
    require(all(base[k]==[] for k in ('meshes','meshMetadata','rawImages','imageGroups','deformers','atlases')) and base['canvas']==s['canvas']==[1024,1024] and s['deformers']==s['atlases']==[],'isolated canvas and authoring baseline')
    original={p[0]:p for p in base['parameters']};parameters={p[0]:p for p in s['parameters']};added=set(parameters)-set(original)
    require(len(parameters)==len(s['parameters']) and all(parameters.get(k)==v for k,v in original.items()),'original parameters unchanged')
    yaw=[p for p in added if re.fullmatch(r'Param3DYaw[0-9a-f]{12}',p)]
    pitch=[p for p in added if re.fullmatch(r'Param3DPitch[0-9a-f]{12}',p)]
    roll=[p for p in added if re.fullmatch(r'Param3DRoll[0-9a-f]{12}',p)]
    require(len(added)==3 and len(yaw)==len(pitch)==len(roll)==1 and yaw[0][-12:]==pitch[0][-12:]==roll[0][-12:],'generated axis IDs')
    require(all(parameters[p][1:]==[-180,0,180,0] for p in (yaw+pitch+roll)),'generated parameter domains')
    snapshots=s['meshes'];require(len(snapshots)==2 and len({m['artMeshId'] for m in snapshots})==len({m['sourceGuid'] for m in snapshots})==2,'distinct native source identities')
    covered=set()
    for m in snapshots:
        require(set(m)=={'artMeshId','sourceGuid','positions','uvs','indices','axes','cells','forms','morphKeys'},'snapshot fields')
        require(m['morphKeys']==[] and m['indices']==[0,1,2] and len(m['positions'])==len(m['uvs'])==3,'single source triangle identity')
        uvs=[(f32(u),f32(v)) for u,v in m['uvs']]
        require(tuple(uvs) in triples or frozenset(uvs) in {frozenset(t) for t in triples},'stored uvs are padded source uvs')
        covered.add(frozenset(uvs))
        require([[f32(x),f32(y)] for x,y in m['positions']]==[[f32(u*TEXTURE[0]),f32(v*TEXTURE[1])] for u,v in uvs],'stored texture-space source geometry')
        axes=m['axes'];require(len(axes)==2 and {a['id'] for a in axes}=={yaw[0],pitch[0]} and len({a['guid'] for a in axes})==2,'bound axis identity')
        for a in axes:
            require(a['minimum']==-180 and a['maximum']==180 and a['default']==0 and sorted(map(f32,a['keys']))==AXES_KEYS,'axis domain/keys')
        require(len(m['cells'])==len(m['forms'])==81,'9x9 dense grid count')
        by_guid={f['guid']:f for f in m['forms']};require(len(by_guid)==81,'form identities')
        seen=set()
        for cell in m['cells']:
            coords={v['guid']:v['value'] for v in cell['coordinates']}
            require(set(coords)=={a['guid'] for a in axes},'complete cell tuple')
            pose=tuple(f32(coords[a['guid']]) for a in axes)
            require(pose not in seen and all(v in AXES_KEYS for v in pose),'cell tuple missing/duplicated/undeclared')
            seen.add(pose)
            form=by_guid.get(cell['formGuid']);require(form is not None,'dangling form reference')
            require(type(form['drawOrder']) is int and 0<=form['drawOrder']<=1000 and len(form['positions'])==3,'form draw order/positions')
        require(seen=={(x,y) for x in AXES_KEYS for y in AXES_KEYS},'joint grid coverage')
    require({frozenset(t) for t in triples}==covered,'every source triangle imported exactly once')
    metadata=s['meshMetadata'];require(len(metadata)==2 and [m['id'] for m in metadata]==[m['artMeshId'] for m in snapshots],'complete bound mesh metadata')
    images=[i for g in s['imageGroups'] for i in g['images']]
    require(len(s['rawImages'])==1 and len(images)>=1 and all(i[2:]==[512,256] for i in s['rawImages']+images),'padded source image identity')
    require(all(m['visible'] is True and m['culling'] is True and m['input']['kind']=='MODEL_IMAGE' and m['input']['image'] for m in metadata),'complete source image binding')
    parts={p[0] for p in s['parts']}-{p[0] for p in base['parts']}
    require(len(parts)>=2 and {m['parent'] for m in metadata}<=parts,'native import parts')
    return dict(status='VERTEX_SURFACE_MENU_SOURCE_IDENTITY_UNDO_REDO_CHECKED',meshes=2,formsPerMesh=81,axesKeys=[9,9],
                storedFormGeometryOracle='NOT_VERIFIED_VERTEX_INTERPOLATION_MATH_NOT_REIMPLEMENTED')

def png_ok(raw):
    if raw[:8]!=b'\x89PNG\r\n\x1a\n' or len(raw)<64:return False
    w,h=struct.unpack('>II',raw[16:24])
    if (w,h)!=(1024,1024):return False
    return len(set(raw[::max(1,len(raw)//64)]))>8

def verify_capture(report,glb,run,artifact_reader):
    ui=verify_ui(report,glb,run)
    s=report['sourceTextureRender']
    require(report['renderVerification']=='CAPTURED_REQUIRES_INDEPENDENT_ORACLE'
            and s['schemaVersion']=='mi3d-source-texture-render-v1' and s['status']=='CAPTURED'
            and 'failure' not in s and s['restored'] is True,'complete/restored render capture')
    raw=s['protectedStateJson'];digest=hashlib.sha256(raw.encode('utf-8')).hexdigest()
    protected=dict(report['imported'],parameters=[p[:4] for p in report['imported']['parameters']])
    require(json.loads(raw)==protected and s['protectedStateSha256']==digest,'protected authored state identity')
    require(s['canvas'][:2]==[1024,1024],'canvas definition')
    yaw,pitch=s['axes'];require(re.fullmatch(r'Param3DYaw[0-9a-f]{12}',yaw) and re.fullmatch(r'Param3DPitch[0-9a-f]{12}',pitch),'render axes')
    root=s['entryHistory'];restored=s['restoredHistory']
    def history(h):
        require(all(type(e) is str and e for e in h['entries']) and len(set(h['entries']))==len(h['entries']),'stable history identities')
        require(h['availability']=='AVAILABLE' and 0<=h['position']<=len(h['entries']),'history values')
        require(all(h[k]==root[k] for k in ('generation','document','manager')) and h['document'] and h['manager'],'history binding')
    history(root);history(restored)
    frames=s['frames'];require(len(frames)==9,'capture frame inventory')
    for i,(frame,pose) in enumerate(zip(frames,POSES)):
        require(frame['index']==i and frame['pose']==list(pose) and frame['artifact']==f'source-texture-render/{i}.png','fixed frame pose/name')
        png=artifact_reader(frame['artifact'])
        require(0<len(png)==frame['artifactBytes']<=8*1024*1024 and hashlib.sha256(png).hexdigest()==frame['artifactSha256'],'native PNG identity')
        require(png_ok(png),'PNG decodes to 1024 canvas with content')
        require(frame['waitReason']=='CONSUMED' and frame['stateBeforeSha256']==frame['stateAfterSha256']==digest,'pose/state protocol')
        require(frame['historyBefore']==frame['historyAfter'] and all(len(frame[k])==2 for k in ('requested','lastObservation','evaluationBefore','evaluationAfter')),'render integrity')
    distinct=len({f['artifactSha256'] for f in frames})
    require(distinct>=4,'poses produce visibly distinct renders')
    return dict(status='VERTEX_SURFACE_MENU_CAPTURE_CHECKED_NO_PIXEL_ORACLE',ui=ui,frames=len(frames),
                distinctPoseImages=distinct,pixelCorrectnessAccepted=False,
                pixelOracle='NOT_REIMPLEMENTED_FOR_ELLIPSE_INTERPOLATION')
