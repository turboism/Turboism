"""Packed-atlas persistence content checks; callers must admit both queue jobs separately."""
import copy
import hashlib
from pathlib import Path
import runpy

W=runpy.run_path(str(Path(__file__).with_name('verify-whole-import.py')))
C=W['CUBE'];R=W['RENDER']


def require(ok,message):
    if not ok:raise ValueError('ATLAS_PERSISTENCE: '+message)


def packed(capture,raw_reference,cube,*,texture=None,padded=False):
    """Ground packed UVs in original source UVs and the explicit atlas placement."""
    W['verify_cube_persistence_capture'](raw_reference,cube,texture=texture,padded=padded)
    width,height,size = (512,256,1024) if padded else (256,128,512)
    require(set(capture)=={'cube','atlas','layout'},'packed capture fields')
    atlas,layout=capture['atlas'],capture['layout']
    require(len(atlas)==5 and isinstance(atlas[0],str) and atlas[0]
            and atlas[1:]==['Turboism Atlas 1',size,size,1],'atlas resource')
    image=raw_reference['state']['imageGroups'][0]['images'][0][0]
    # SDK layout atlasId is the page name; model texture input uses the native GUID.
    # Keep these two domains distinct rather than substituting one for the other.
    require(set(layout)=={'atlasId','items','plan'} and layout['atlasId']==atlas[1]
            and layout['items']==[[image,width,height]],'layout page/image identity')
    plan=layout['plan']
    require(set(plan)=={'width','height','pages','names','scale','placements'}
            and plan['width']==plan['height']==size and plan['pages']==1 and plan['scale']==1
            and plan['names']==[atlas[1]] and len(plan['placements'])==1,'layout plan')
    placement=plan['placements'][0]
    require(len(placement)==7 and placement[:2]==[image,0] and placement[4:]==[width,height,False]
            and all(type(v) is int for v in placement[1:6]) and 2<=placement[2]<=size-width-2
            and 2<=placement[3]<=size-height-2,'bounded image placement')
    x,y=placement[2:4];observed=capture['cube'];normalized=copy.deepcopy(observed)
    require(len(observed['snapshots'])==len(observed['state']['meshes'])==6,'six packed meshes')
    for i,(mesh,source_mesh) in enumerate(zip(observed['state']['meshes'],raw_reference['state']['meshes'])):
        source=raw_reference['snapshots'][i];snapshot=observed['snapshots'][i]
        require(mesh['input']==dict(kind='TEXTURE_ATLAS_REGION',meshId=source_mesh['id'],imageId='',atlasId=atlas[0]),'actual atlas binding')
        require([[C['f32'](u),C['f32'](v)] for u,v in snapshot['uvs']]==[[C['f32']((width*C['f32'](u)+x)/size),C['f32']((height*C['f32'](v)+y)/size)] for u,v in source['uvs']]
                and mesh['snapshotSha256']==R['fingerprint'](snapshot),'source-to-atlas UV transform')
        normalized['snapshots'][i]['uvs']=copy.deepcopy(source['uvs'])
        normalized['state']['meshes'][i]['snapshotSha256']=R['fingerprint'](normalized['snapshots'][i])
        normalized['state']['meshes'][i]['input']=copy.deepcopy(source_mesh['input'])
    require(normalized==raw_reference,'packed state changed geometry/resources/parameters')
    return dict(forms=12150,atlasGuid=atlas[0],atlasPageName=atlas[1])


def pair(save,reopen,cmo3,source_sha,copy_sha,raw_reference,cube,save_frames,reopen_frames,*,texture=None,padded=False):
    """Sections plus byte artifacts only; not a substitute for per-job/run admission."""
    require(type(padded) is bool and padded == (texture is not None),'explicit atlas pair source profile')
    expected_input = ('dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859'
                      if padded else C['INPUT_SHA256'])
    for s,mode in ((save,'atlas-save'),(reopen,'atlas-reopen')):
        require(s['schemaVersion']=='mi3d-cube-atlas-persistence-v1' and s['mode']==mode
                and s['status']=='PASS' and s['failure'] is None and s['renderStatus']=='CAPTURED'
                and s['inputSha256']==expected_input,'section protocol')
        if padded:
            require(s.get('textureLayout')=='EDGE_PADDED_POT' and s.get('registeredImageSize')==[512,256],
                    'atlas pair source layout')
        p=s['process'];require(set(p)=={'pid','startEpochMillis'} and all(type(v) is int and v>0 for v in p.values()),'process identity')
        require(W['restored_history'](s['historyBefore'],s['historyAfter']),'authored history changed')
    require(save['process']!=reopen['process'],'fresh process required')
    require(0<len(cmo3)<=32*1024*1024 and save['artifactBytes']==len(cmo3)
            and save['artifact']=='persistence/mi3d-cube-model.cmo3','saved artifact')
    sha=hashlib.sha256(cmo3).hexdigest()
    require(sha==save['artifactSha256']==source_sha==copy_sha,'exact saved file must be the reopen seed')
    before=save['beforeSave']
    require(before==save['afterSave']==reopen['reopened']==reopen['afterDisplay']==reopen['afterObservation'],'cross-process packed state differs')
    packed(before,raw_reference,cube,texture=texture,padded=padded)
    phases=((save,'atlas-before-save','beforeSaveFrames',save_frames),(save,'atlas-after-save','afterSaveFrames',save_frames),
            (reopen,'atlas-reopen','reopenFrames',reopen_frames))
    for s,phase,key,frames in phases:
        progress=s['renderProgress-'+phase]
        require(progress['stage']=='complete' and progress['entryState']==progress['restoredState']==before['cube']['state']
                and W['restored_history'](progress['entryHistory'],progress['restoredHistory']),'render restoration')
        if padded:
            R['verify_persistence_frames'](s[key],before['cube'],phase,cube,frames.__getitem__,texture=texture,padded=True)
        else:
            R['verify_persistence_frames'](s[key],before['cube'],phase,cube,frames.__getitem__)
    require(set(save_frames)=={f'cube-persistence-render/atlas-{p}-save-{i}.png' for p in ('before','after') for i in range(9)}
            and set(reopen_frames)=={f'cube-persistence-render/atlas-reopen-{i}.png' for i in range(9)},'frame inventory')
    for i in range(9):
        require(R['PNG'](save_frames[f'cube-persistence-render/atlas-before-save-{i}.png'])==
                R['PNG'](save_frames[f'cube-persistence-render/atlas-after-save-{i}.png'])==
                R['PNG'](reopen_frames[f'cube-persistence-render/atlas-reopen-{i}.png']),'persisted atlas pixels differ')
    return dict(status='ATLAS_PAIR_CONTENT_CHECKED_NOT_PROCESS_ADMITTED',forms=12150,poses=9,artifactSha256=sha)


def report_leg(report,run_id,mode,cube,frames,cmo3=None,raw_reference=None,*,texture=None,padded=False):
    """Per-job content gate; the host verifier admits lifecycle/archive first."""
    require(type(padded) is bool and padded == (texture is not None),'explicit atlas leg source profile')
    expected_input = ('dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859'
                      if padded else C['INPUT_SHA256'])
    require(mode in ('cube-atlas-save','cube-atlas-reopen') and report['mi3dMode']==mode
            and report['runId']==run_id and report['status']=='PASS' and report['failure'] is None
            and report['finallyRestored'] is True and report['finallyCounts']==report['initialCounts'],
            'terminal/recovery')
    require(report['seed']==report['document'] and all(isinstance(report['seed'][k],str) and report['seed'][k]
            for k in ('documentId','modelId','editorModelId','kind')),'launcher seed identity')
    for name in ('ordinaryWrite','evaluation','order','morph','morphOrder','textureBinding','pixelSampling',
                 'shapeImport','wholeImport','cubeRender','cubeGeometry','jointPose','persistenceSave',
                 'persistenceReopen','cubePersistence','cubeExport'):
        require(report[name]['status']=='NOT_RUN','unexpected probe '+name)
    s=report['cubeAtlasPersistence']
    require(s['schemaVersion']=='mi3d-cube-atlas-persistence-v1' and s['mode']==mode.removeprefix('cube-')
            and s['status']=='PASS' and s['failure'] is None and s['renderStatus']=='CAPTURED'
            and s['inputSha256']==expected_input,'section protocol')
    if padded:
        require(s.get('textureLayout')=='EDGE_PADDED_POT' and s.get('registeredImageSize')==[512,256],'atlas leg source layout')
    process=s['process']
    require(set(process)=={'pid','startEpochMillis'} and all(type(v) is int and v>0 for v in process.values()),'process identity')
    W['history'](s['historyBefore'],s['historyBefore']);W['history'](s['historyAfter'],s['historyBefore'])
    require(W['restored_history'](s['historyBefore'],s['historyAfter']),'authored history changed')
    if mode=='cube-atlas-save':
        atlas_phases=('atlas-baseline','atlas-packed','atlas-repacked','atlas-redone')
        atlas_names={f'cube-persistence-render/{phase}-{i}.png' for phase in atlas_phases for i in range(9)}
        W['verify_cube_atlas_report'](report,run_id,cube,{n:frames[n] for n in atlas_names},expected_mode=mode,texture=texture,padded=padded)
        raw_reference=report['cubeAtlas']['initial'];before=s['beforeSave']
        require(before==s['afterSave'] and before['cube']==report['cubeAtlas']['redone']
                and before['atlas']==report['cubeAtlas']['repackedAtlases'][0],'save differs from repacked result')
        layout={k:v for k,v in report['cubeAtlas']['redoLayout'].items() if k not in ('documentId','modelId')}
        require(before['layout']==layout,'save layout differs from redone layout')
        require(isinstance(cmo3,bytes) and 0<len(cmo3)<=32*1024*1024
                and type(s['artifactBytes']) is int and s['artifactBytes']==len(cmo3)
                and s['artifact']=='persistence/mi3d-cube-model.cmo3'
                and s['artifactSha256']==hashlib.sha256(cmo3).hexdigest(),'saved artifact')
        phases=(('atlas-before-save','beforeSaveFrames'),('atlas-after-save','afterSaveFrames'))
    else:
        require(raw_reference is not None,'reopen requires admitted save reference')
        require(cmo3 is None and report['cubeAtlas']['status']=='NOT_RUN','reopen wrote artifact or packed again')
        before=s['reopened'];require(before==s['afterDisplay']==s['afterObservation'],'reopen changed authored state')
        atlas_names=set();phases=(('atlas-reopen','reopenFrames'),)
    packed(before,raw_reference,cube,texture=texture,padded=padded)
    expected=atlas_names | {f'cube-persistence-render/{phase}-{i}.png' for phase,_ in phases for i in range(9)}
    require(set(frames)==expected,'leg frame inventory')
    for phase,key in phases:
        progress=s['renderProgress-'+phase]
        require(progress['stage']=='complete' and progress['entryState']==progress['restoredState']==before['cube']['state']
                and W['restored_history'](progress['entryHistory'],progress['restoredHistory']),'render restoration')
        if padded:
            R['verify_persistence_frames'](s[key],before['cube'],phase,cube,frames.__getitem__,texture=texture,padded=True)
        else:
            R['verify_persistence_frames'](s[key],before['cube'],phase,cube,frames.__getitem__)
    if mode=='cube-atlas-save':
        for i in range(9):
            require(R['PNG'](frames[f'cube-persistence-render/atlas-before-save-{i}.png'])==
                    R['PNG'](frames[f'cube-persistence-render/atlas-after-save-{i}.png']),'save changed pixels')
    return dict(forms=12150,frames=len(frames))


def admitted_pair(a,b,cube,*,texture=None,padded=False):
    """Both arguments must have passed host job_result; bind their lifecycle identities and seeds."""
    require(a['mode']=='cube-atlas-save' and b['mode']=='cube-atlas-reopen','pair modes')
    require(all(a['lifecycle'][k]!=b['lifecycle'][k] for k in ('jobId','attemptId','runId'))
            and a['report']['runId']!=b['report']['runId'],'distinct job/run/attempt required')
    details=b['lifecycle']['details'];checks=details['postContainmentChecks']
    sha=hashlib.sha256(a['cmo3']).hexdigest()
    require(sha==details['sourceFixtureBeforeSha256']==details['fixtureBeforeSha256'],'reopen initial seed')
    save_frames={n:data for n,data in a['cubePersistenceFrames'].items()
                 if n.startswith(('cube-persistence-render/atlas-before-save-','cube-persistence-render/atlas-after-save-'))}
    result=pair(a['report']['cubeAtlasPersistence'],b['report']['cubeAtlasPersistence'],a['cmo3'],
                checks['fixtureSourceSha256'],checks['fixtureCopySha256'],a['report']['cubeAtlas']['initial'],
                cube,save_frames,b['cubePersistenceFrames'],texture=texture,padded=padded)
    return dict(result,status='EXACT_HOST_ATLAS_PERSISTENCE_PAIR_PASS')
