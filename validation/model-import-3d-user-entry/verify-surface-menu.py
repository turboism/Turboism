#!/usr/bin/env python3
"""Fixed source plane / discontinuous seam menu authoring and native raster oracle."""
import hashlib,json,math,re,runpy,struct
from pathlib import Path

HERE=Path(__file__).resolve().parent
G=runpy.run_path(str(HERE.parent/'model-import-3d-host-probe/verify-cube-geometry.py'))
PIXELS=runpy.run_path(str(HERE.parent/'model-import-3d-host-probe/verify-planar-pixels.py'))
PNG=runpy.run_path(str(HERE.parent/'model-import-3d-host-probe/verify-cube-render.py'))['PNG']
HASHES={'plane-menu':'8aa60f903cee58d123831d6b66315b0537bafffcf68a064bcb6a0b0132d1b997','seam-menu':'c9605e030d4f174501d8f98f798dcad3a5a3851254b347b1b80e882fee8484dd'}
KEYS=[G['f32'](-30+1.25*i) for i in range(49)]
POSES=PIXELS['POSES']
SCALE=400/math.sqrt(1.25)


def require(ok,why):
    if not ok:raise ValueError('SURFACE_MENU: '+why)


def truth(vertices,yaw,pitch):
    y,p=math.radians(yaw),math.radians(pitch)
    return [[512+SCALE*(math.cos(y)*x+math.sin(y)*math.sin(p)*v+math.sin(y)*math.cos(p)*z),512-SCALE*(math.cos(p)*v-math.sin(p)*z)] for x,v,z in vertices]


def sources(glb):
    raw=Path(glb).read_bytes();digest=hashlib.sha256(raw).hexdigest();require(digest in HASHES.values(),'exact declared source')
    positions,uv,indices,image=PIXELS['source'](raw)
    slots=[list(dict.fromkeys(indices))] if digest==HASHES['plane-menu'] else [list(indices[:3]),list(indices[3:])]
    patches=[];vertices=[]
    for group in slots:
        local_indices=[group.index(i) for i in indices] if len(group)==4 else [0,1,2]
        patches.append(dict(uvs=[[G['f32']((256*uv[i][0]+1)/512),G['f32']((128*uv[i][1]+1)/256)] for i in group],indices=local_indices))
        vertices.append([positions[i] for i in group])
    require(sum(len(v) for v in vertices)==(4 if digest==HASHES['plane-menu'] else 6),'source slot identity preserved')
    return raw,vertices,patches


def verify_ui(r,glb,run):
    raw,vertices,patches=sources(glb);count=len(vertices)
    require(r['schemaVersion']=='mi3d-surface-menu-v1' and r['scope']=='REAL_MENU_IMPORT_UNDO_REDO_SOURCE_RENDER' and r['runId']==run and r['status']=='PASS' and 'failure' not in r,'explicit surface menu scope')
    require(r['textureLayout']=='EDGE_PADDED_POT' and r['inputSha256']==hashlib.sha256(raw).hexdigest() and r['appearance']=='SOURCE_MATERIAL','source material identity')
    require(r['seedDocumentId']!=r['targetDocumentId'] and r['menuInvoked'] is True and r['realChooserApproved'] is True and r['completionMessage'].startswith('导入完成'),'real menu and chooser')
    require(all(text in r['confirmation'] for text in (f'{count} 个网格',str(count*2401),'49 / 49')),'actual declared dense plan')
    base,s=r['baseline'],r['imported'];require(r['undoRestored'] is True and r['afterUndo']==base and r['redoRestored'] is True and r['afterRedo']==s,'complete single Undo/Redo')
    h=r['historyBefore'];require(r['historyAfter']==r['historyAfterRedo']==h+1 and r['historyAfterUndo']==h,'one native import history entry')
    require(all(base[k]==[] for k in ('meshes','meshMetadata','rawImages','imageGroups','deformers','atlases')) and base['canvas']==s['canvas']==[1024,1024] and s['deformers']==s['atlases']==[],'isolated canvas and authoring baseline')
    original={p[0]:p for p in base['parameters']};parameters={p[0]:p for p in s['parameters']};added=set(parameters)-set(original)
    require(len(parameters)==len(s['parameters']) and len(original)==len(base['parameters']) and all(parameters.get(k)==v for k,v in original.items()),'original parameters unchanged')
    yaw=[p for p in added if re.fullmatch(r'Param3DYaw[0-9a-f]{12}',p)];pitch=[p for p in added if re.fullmatch(r'Param3DPitch[0-9a-f]{12}',p)]
    require(len(added)==2 and len(yaw)==len(pitch)==1 and yaw[0][-12:]==pitch[0][-12:],'generated axis IDs')
    axes=[yaw[0],pitch[0]];require(all(parameters[p][1:]==[-30,0,30,0] for p in axes),'frozen parameter domains')
    snapshots=s['meshes'];require(len(snapshots)==count and len({m['artMeshId'] for m in snapshots})==len({m['sourceGuid'] for m in snapshots})==count,'distinct native source identities')
    for snapshot,points,patch in zip(snapshots,vertices,patches):
        G['stored'](snapshot,points,True,patch,texture_source=True,texture_size=(512,256),keys=KEYS,parameter_ids=axes,projection=truth)
        require(all(f['drawOrder']==500 for f in snapshot['forms']),'fixed planar draw order')
    metadata=s['meshMetadata'];require(len(metadata)==count and [m['id'] for m in metadata]==[m['artMeshId'] for m in snapshots],'complete bound mesh metadata')
    images=[i for g in s['imageGroups'] for i in g['images']];require(len(s['rawImages'])==len(images)==1 and all(i[2:]==[512,256] for i in s['rawImages']+images),'one padded original source image')
    require(all(m['visible'] is True and m['culling'] is True and m['input']==dict(kind='MODEL_IMAGE',image=images[0][0],atlas='') for m in metadata),'complete source image binding')
    parts={p[0] for p in s['parts']}-{p[0] for p in base['parts']};require(len(parts)==1 and {m['parent'] for m in metadata}==parts,'shared native control Part')
    return dict(status='FIXED_SURFACE_MENU_STORED_GEOMETRY_UNDO_REDO_CHECKED',meshes=count,sourceVertexSlots=sum(map(len,vertices)),forms=count*2401,axesKeys=[49,49],camera=[512,512,SCALE,0,0,0],maxStoredErrorPixels=.0001,goalComplete=False)


def verify_frames(frames,glb,reader):
    raw=Path(glb).read_bytes();require(len(frames)==9,'nine actual raster frames');results=[]
    for i,(frame,pose) in enumerate(zip(frames,POSES)):
        name=f'source-texture-render/{i}.png';require(frame['index']==i and frame['pose']==list(pose) and frame['artifact']==name,'fixed frame pose/name')
        png=reader(name);require(0<len(png)==frame['artifactBytes']<=8*1024*1024 and hashlib.sha256(png).hexdigest()==frame['artifactSha256'],'actual native PNG identity')
        result=PIXELS['inspect'](PNG(png),raw,pose,framed=True)
        require(result['mismatches']==0,f'pose {pose}: {result["firstMismatch"]}')
        results.append(dict(result,pose=pose))
    return dict(status='FIXED_SOURCE_SURFACE_RASTER_ONLY',frames=results,toleranceRGB=2,edgeExclusionPixels=1,sampleGridPixels=4,pixelCentreSampling=True)


def verify_capture(report,glb,run,artifact_reader):
    """Bind raster to admitted UI import, unchanged authored state and fresh poses.

    Caller still admits bundle/job/process identities and artifact namespace.
    """
    ui=verify_ui(report,glb,run)
    return dict(status='UI_SOURCE_TEXTURE_CONTENT_CHECKED_NOT_PROCESS_ADMITTED',ui=ui,
                pixels=verify_render(report,glb,artifact_reader,report['imported'],report['historyAfterRedo']))


def verify_render(report,glb,artifact_reader,stored_state,history_position):
    section=report['sourceTextureRender']
    require(report['renderVerification']=='CAPTURED_REQUIRES_INDEPENDENT_ORACLE'
            and section['schemaVersion']=='mi3d-source-texture-render-v1'
            and section['status']=='CAPTURED' and 'failure' not in section
            and section['restored'] is True,'complete/restored render capture')
    raw=section['protectedStateJson'];digest=hashlib.sha256(raw.encode('utf-8')).hexdigest()
    protected=dict(stored_state,parameters=[p[:4] for p in stored_state['parameters']])
    require(json.loads(raw)==protected and section['protectedStateSha256']==digest,'protected authored state identity')
    canvas=section['canvas']
    require(len(canvas)==5 and canvas[:2]==[1024,1024] and all(type(v) in (int,float) and math.isfinite(v) for v in canvas),'canvas definition')
    snapshots=stored_state['meshes'];parameters=stored_state['parameters']
    yaw,pitch=section['axes']
    require(yaw.startswith('Param3DYaw') and pitch.startswith('Param3DPitch')
            and {yaw,pitch}=={a['id'] for a in snapshots[0]['axes']},'render parameter axes')
    root=section['entryHistory'];restored=section['restoredHistory']
    def history(h):
        require(all(type(e) is str and bool(e) for e in h['entries'])
                and len(set(h['entries']))==len(h['entries']),'stable history entry identities')
        require(h['availability']=='AVAILABLE' and type(h['position']) is int
                and 0<=h['position']<=len(h['entries']) and type(h['revision']) is int and h['revision']>=root['revision'], 'history values')
        require(all(h[k]==root[k] for k in ('generation','document','manager'))
                and bool(h['document']) and bool(h['manager']),'history binding')
        require(h['entries'][:root['position']]==root['entries'][:root['position']],'import history prefix changed')
    history(root);history(restored)
    require(root['position']==restored['position']==history_position,'render restore history position')
    _,source_points,_=sources(glb);count=len(source_points)
    frames=section['frames'];require(len(frames)==9,'capture frame inventory')
    identity=None
    for frame,pose in zip(frames,POSES):
        require(frame['pose']==list(pose) and frame['waitReason']=='CONSUMED'
                and type(frame['elapsedMillis']) is int and 0<=frame['elapsedMillis']<15000
                and type(frame['attempts']) is int and frame['attempts']>0,'pose/wait protocol')
        require(frame['canvasBefore']==frame['canvasAfter']==canvas,'canvas drift')
        require(frame['stateBeforeSha256']==frame['stateAfterSha256']==digest,'render authored state drift')
        history(frame['historyBefore']);history(frame['historyAfter'])
        require(frame['historyBefore']==frame['historyAfter'],'render history drift')
        require(all(len(frame[k])==count for k in ('requested','lastObservation','evaluationBefore','evaluationAfter')),'complete surface observations')
        expected={p[0]:p[4] for p in parameters};expected.update({yaw:pose[0],pitch:pose[1]})
        for i,snapshot in enumerate(snapshots):
            requested=frame['requested'][i];actual=frame['lastObservation'][i]
            def values(state):
                rows=state['values'];require(len(rows)==len(expected),'parameter observation inventory')
                require(len({x[0] for x in rows})==len({x[1] for x in rows})==len(rows),'duplicate parameter identities')
                require({x[1]:x[2] for x in rows}==expected,'unexpected live/consumed pose')
                return sorted(rows)
            wanted=values(requested['live'])
            mapping=sorted((v[0],v[1]) for v in wanted)
            if identity is None:identity=mapping
            require(identity==mapping,'parameter GUID/ID drift')
            for axis in snapshot['axes']:
                require((axis['guid'],axis['id']) in identity,'authored/observed parameter identity')
            for obs in (requested,actual,frame['evaluationBefore'][i],frame['evaluationAfter'][i]):
                require(obs['artMeshId']==snapshot['artMeshId'] and obs['sourceGuid']==snapshot['sourceGuid'],'render mesh identity')
                require(values(obs['live'])==wanted,'live values drift')
            require(values(actual['consumed'])==wanted
                    and actual['consumed']['version']!=requested['consumed']['version']
                    and actual['passEntryTimeMillis']>=max(0,requested['passEntryTimeMillis']),'stale consumption')
            calculated=actual['calculated']
            require(calculated['interpolated'] is True and calculated['coordinateType'] in ('Canvas','DeformerLocal')
                    and calculated['drawOrder']==500
                    and G['error'](calculated['positions'],truth(source_points[i],*pose))<=.1,'native geometry error')
            for obs in (frame['evaluationBefore'][i],frame['evaluationAfter'][i]):
                require(values(obs['consumed'])==wanted and obs['calculated']==calculated,'render geometry/consumption changed')
            require(frame['evaluationBefore'][i]==frame['evaluationAfter'][i],'evaluation changed during render')
    return verify_frames(frames,glb,artifact_reader)
