"""Independent whole-import transaction and stored-geometry gate; not a rendering/export gate."""
from pathlib import Path
import runpy
import re

CUBE = runpy.run_path(str(Path(__file__).with_name('verify-cube-geometry.py')))
RENDER = runpy.run_path(str(Path(__file__).with_name('verify-cube-render.py')))
STAGES = ['AXES', 'GROUP', 'GEOMETRY', 'BAKE', 'MATERIAL', 'VISIBILITY']
STATE_KEYS = {'model', 'meshes', 'canvas', 'parameters', 'parts', 'rawImages', 'imageGroups'}
HISTORY_KEYS = {'availability', 'generation', 'revision', 'position', 'documentBindingId', 'managerBindingId', 'entryIds'}


def require(ok, message):
    if not ok:
        raise ValueError('WHOLE_IMPORT: ' + message)


def history(value, root):
    require(isinstance(value, dict) and set(value) == HISTORY_KEYS, 'history fields')
    CUBE['history']({k:v for k,v in value.items() if k != 'entryIds'},
                    {k:v for k,v in root.items() if k != 'entryIds'})
    require(isinstance(value['entryIds'], list) and len(value['entryIds']) >= value['position']
            and all(isinstance(v, str) for v in value['entryIds']), 'history entry inventory')


def state(value):
    require(isinstance(value, dict) and set(value) == STATE_KEYS, 'state fields')
    require(isinstance(value['model'], str) and value['model'], 'model identity')
    require(len(value['canvas']) == 5 and all(CUBE['number'](v) for v in value['canvas']), 'canvas')
    require(all(isinstance(value[k], list) for k in STATE_KEYS - {'model', 'canvas'}), 'state inventories')
    ids = []
    for mesh in value['meshes']:
        require(set(mesh) == {'id', 'snapshotSha256', 'culling', 'visible', 'parent', 'input'}, 'mesh state fields')
        require(isinstance(mesh['id'], str) and mesh['id'] and re.fullmatch('[0-9a-f]{64}', mesh['snapshotSha256'])
                and type(mesh['culling']) is bool and type(mesh['visible']) is bool, 'mesh state identity/flags')
        ids.append(mesh['id'])
    require(len(ids) == len(set(ids)), 'duplicate mesh state')
    for key, width in [('parameters',8),('parts',3),('rawImages',4)]:
        require(all(isinstance(v,list) and len(v)==width and isinstance(v[0],str) and v[0] for v in value[key]), key+' rows')
        require(len({v[0] for v in value[key]}) == len(value[key]), key+' duplicate identities')


def verify_whole_import(report, input_path):
    section = report['wholeImport']
    require(section['schemaVersion'] == 'mi3d-whole-import-v1' and section['status'] == 'PASS'
            and section['failure'] is None and section['finallyRestored'] is True, 'terminal result')
    require(section['inputSha256'] == CUBE['INPUT_SHA256'] and section['renderUvTransform'] == [.5,.25,.5,.25], 'input/UV transform')
    initial, root = section['initialState'], section['initialHistory']
    state(initial); history(root, root)
    require(initial['meshes'] and initial['parameters'], 'preexisting authored content required')
    failures = section['failures']
    require(isinstance(failures,list) and len(failures)==len(STAGES), 'failure inventory')
    for i, row in enumerate(failures):
        require(row['stage']==STAGES[i] and row['reached']==STAGES[:i+1], 'injected stage not reached exactly')
        require(row['outcome']=='ROLLED_BACK' and isinstance(row['diagnosticId'],str) and row['diagnosticId'], 'failure outcome')
        state(row['afterState']);history(row['beforeHistory'],root);history(row['afterHistory'],root)
        require(row['afterState']==initial and row['beforeHistory']==row['afterHistory']==root,
                'failed import left content/resources/history changes')
    require(section['outcome']=='COMMITTED' and section['diagnosticId'] is None and section['reached']==STAGES, 'success outcome')
    for key in ('beforeHistory','committedHistory','undoneHistory','redoneHistory','restoredHistory'):
        history(section[key],root)
    require(section['beforeHistory']==root and section['committedHistory']['position']==root['position']+1, 'not one native edit')
    require(section['undoneHistory']['position']==section['restoredHistory']['position']==root['position']
            and section['redoneHistory']['position']==root['position']+1, 'Undo/Redo/final position')
    for key in ('committedState','undoneState','redoneState','restoredState'):
        state(section[key])
    committed=section['committedState']
    require(section['undoneState']==section['restoredState']==initial and section['redoneState']==committed, 'Undo/Redo content/resource mismatch')
    require(committed['model']==initial['model'] and committed['canvas']==initial['canvas'], 'model/canvas changed')
    ids=section['meshIds']; snapshots=section['written'];part=section['partId']
    require(len(ids)==len(set(ids))==len(snapshots)==6 and part, 'import identities')
    require([m for m in committed['meshes'] if m['id'] not in ids]==initial['meshes']
            and len(committed['meshes'])==len(initial['meshes'])+6, 'preexisting meshes changed')
    require([p for p in committed['parts'] if p[0]!=part]==initial['parts']
            and len(committed['parts'])==len(initial['parts'])+1, 'part delta')
    parameters=CUBE['PARAMETERS']
    require([p for p in committed['parameters'] if p[0] not in parameters]==initial['parameters']
            and len(committed['parameters'])==len(initial['parameters'])+2, 'parameter delta')
    added=[p for p in committed['parameters'] if p[0] in parameters]
    require({p[0] for p in added}==set(parameters) and all(p[2:]==['NORMAL',0,-30,0,30,False] for p in added), 'import axis definitions')
    require(committed['rawImages'][:-1]==initial['rawImages'] and len(committed['rawImages'])==len(initial['rawImages'])+1
            and committed['rawImages'][-1][2:]==[256,128], 'one raw image required')
    require(committed['imageGroups'][:-1]==initial['imageGroups'] and len(committed['imageGroups'])==len(initial['imageGroups'])+1,
            'one image group required')
    images=committed['imageGroups'][-1]['images']
    require(len(images)==1 and images[0][2:]==[256,128], 'one shared model image required')
    source, patches=CUBE['cube_source'](input_path)
    rows={m['id']:m for m in committed['meshes']}
    guids=set()
    for i,snapshot in enumerate(snapshots):
        patch=dict(patches[i]);patch['uvs']=[[CUBE['f32'](.25+.5*u),CUBE['f32'](.25+.5*v)] for u,v in patch['uvs']]
        CUBE['stored'](snapshot,source[i],True,patch,texture_source=True)
        require(snapshot['artMeshId']==ids[i], 'stored mesh correspondence')
        guids.add(snapshot['sourceGuid']); row=rows[ids[i]]
        require(row['snapshotSha256']==RENDER['fingerprint'](snapshot) and row['parent']==part
                and row['culling'] is True and row['visible'] is True, 'source/parent/culling readback')
        binding=row['input']
        require(binding and set(binding)=={'kind','modelId','generation','meshId','imageId'}
                and binding['kind']=='MODEL_IMAGE' and binding['modelId']==committed['model']
                and type(binding['generation']) is int and binding['generation']>0
                and binding['meshId']==ids[i] and binding['imageId']==images[0][0], 'material binding identity')
    require(len(guids)==6 and len({rows[i]['input']['generation'] for i in ids})==1, 'source/generation identities')
    return dict(meshes=6, forms=12150, failureStages=6)


def verify_cube_persistence_capture(capture, input_path, *, texture=None, padded=False):
    """Authored-state oracle only; never substitutes for raster or lifecycle/pair admission."""
    require(type(padded) is bool and padded == (texture is not None),
            'source persistence requires explicit padded texture input')
    if padded:
        runpy.run_path(str(Path(__file__).resolve().parents[1] / 'model-import-3d-user-entry/verify-report.py'))['textured_source'](input_path, texture)
    dimensions = [512,256] if padded else [256,128]
    require(set(capture) == {'state', 'snapshots'}, 'persistence capture fields')
    durable = capture['state']
    require(set(durable) == STATE_KEYS - {'model'} | {'schema'}
            and durable['schema'] == 'mi3d-cube-persistence-state-v1', 'durable schema/session fields')
    state(dict({k:v for k,v in durable.items() if k != 'schema'}, model='session-excluded'))
    require(durable['canvas'][:2] == [1024,1024], 'cube canvas')
    meshes = durable['meshes']; snapshots = capture['snapshots']
    require(len(meshes) == len(snapshots) == 6, 'six persisted cube faces required')
    params = {p[0]:p for p in durable['parameters']}
    require(all(p in params and params[p][2:] == ['NORMAL',0,-30,0,30,False] for p in CUBE['PARAMETERS']),
            'persistent cube parameter definitions')
    parts = {p[0]:p for p in durable['parts']}
    parents = {m['parent'] for m in meshes}
    require(len(parents) == 1 and next(iter(parents)) in parts, 'persisted cube Part hierarchy')
    require(len(durable['rawImages']) == len(durable['imageGroups']) == 1
            and durable['rawImages'][0][2:] == dimensions, 'persisted raw image')
    images = durable['imageGroups'][0]['images']
    require(len(images) == 1 and images[0][2:] == dimensions, 'persisted model image')
    source, patches = CUBE['cube_source'](input_path)
    guids = set()
    for i, (row, snapshot) in enumerate(zip(meshes, snapshots)):
        patch = dict(patches[i])
        patch['uvs'] = ([[CUBE['f32']((256*u+1)/512),CUBE['f32']((128*v+1)/256)] for u,v in patch['uvs']]
                        if padded else [[CUBE['f32'](.25+.5*u),CUBE['f32'](.25+.5*v)] for u,v in patch['uvs']])
        CUBE['stored'](snapshot, source[i], True, patch,texture_source=True,texture_size=dimensions)
        require(row['id'] == snapshot['artMeshId'] and row['snapshotSha256'] == RENDER['fingerprint'](snapshot)
                and row['culling'] is True and row['visible'] is True, 'persisted snapshot/visibility')
        guids.add(snapshot['sourceGuid'])
        binding = row['input']
        require(isinstance(binding,dict) and set(binding) == {'kind','meshId','imageId','atlasId'}
                and binding == dict(kind='MODEL_IMAGE',meshId=row['id'],imageId=images[0][0],atlasId=''),
                'persisted source material binding')
    require(len(guids) == 6, 'persisted source identities')
    return dict(meshes=6,forms=12150,renderStatus='NOT_EVALUATED')


def restored_history(before, after):
    history(after,before)
    return ({k:v for k,v in before.items() if k not in ('revision','entryIds')} ==
            {k:v for k,v in after.items() if k not in ('revision','entryIds')}
            and before['entryIds'][:before['position']] == after['entryIds'][:before['position']])


def verify_cube_persistence_report(report, run_id, mode, input_path, frames, cmo3=None, *, texture=None, padded=False):
    import hashlib
    require(type(padded) is bool and padded == (texture is not None), 'explicit persistence report texture')
    expected_input = ('dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859'
                      if padded else CUBE['INPUT_SHA256'])
    expected_uv = [.5,1/512,.5,1/256] if padded else [.5,.25,.5,.25]
    require(mode in ('cube-persistence-save','cube-persistence-reopen') and report['mi3dMode'] == mode,
            'cube persistence mode')
    require(report['runId'] == run_id and report['status'] == 'PASS' and report['failure'] is None
            and report['finallyRestored'] is True and report['finallyCounts'] == report['initialCounts'],
            'cube persistence terminal/recovery')
    for name in ('ordinaryWrite','evaluation','order','morph','morphOrder','textureBinding','pixelSampling',
                 'shapeImport','wholeImport','cubeRender','cubeGeometry','jointPose','persistenceSave','persistenceReopen'):
        require(report[name]['status'] == 'NOT_RUN', 'unexpected other probe in persistence mode')
    seed,document=report['seed'],report['document']
    require(all(isinstance(d[k],str) and d[k] for d in (seed,document)
                for k in ('documentId','modelId','editorModelId','kind')), 'cube document identity')
    if mode == 'cube-persistence-save':
        require(all(seed[k] != document[k] for k in ('documentId','modelId','editorModelId')), 'cube save reused seed')
    else:
        require(seed == document, 'cube reopen did not observe launcher seed')
    section = report['cubePersistence']
    require(section['schemaVersion'] == 'mi3d-cube-persistence-v1' and section['mode'] == mode
            and section['status'] == 'PASS' and section['failure'] is None
            and section['inputSha256'] == expected_input and section['renderUvTransform'] == expected_uv
            and section['renderStatus'] == 'CAPTURED' and section['observedImageBytes'] == 'UNAVAILABLE',
            'cube persistence section')
    if padded:
        require(section.get('textureLayout') == 'EDGE_PADDED_POT'
                and section.get('registeredImageSize') == [512,256], 'source persistence layout metadata')
    process = section['process']
    require(set(process) == {'pid','startEpochMillis'}
            and all(type(v) is int and v > 0 for v in process.values()), 'cube process identity')
    if mode == 'cube-persistence-save':
        before,after = section['beforeSave'],section['afterSave']
        require(before == after, 'SAVE_AS changed cube content')
        require(section['rawDisplayStatus']=='EXECUTED' and section['afterRawDisplay']==before,
                'native raw-image reconstruction changed source mapping')
        verify_cube_persistence_capture(before,input_path,texture=texture,padded=padded)
        require(isinstance(cmo3,bytes) and 0 < len(cmo3) <= 32*1024*1024
                and section['artifact'] == 'persistence/mi3d-cube-model.cmo3'
                and type(section['artifactBytes']) is int and section['artifactBytes'] == len(cmo3)
                and section['artifactSha256'] == hashlib.sha256(cmo3).hexdigest(), 'cube saved artifact identity')
        require(section['meshIds'] == [s['artMeshId'] for s in before['snapshots']]
                and {m['parent'] for m in before['state']['meshes']} == {section['partId']}, 'import identities')
        root=section['initialHistory'];state(section['initialState']);history(root,root)
        require(not section['initialState']['meshes'] and section['finallyRestored'] is True
                and section['restoredState'] == section['initialState'], 'cube initial/restored state')
        for key in ('importHistoryBefore','importHistoryAfter','restoredHistory'):
            history(section[key],root)
        require(section['importHistoryAfter']['position'] == section['importHistoryBefore']['position']+1
                and section['restoredHistory']['position'] == root['position'], 'cube native import/cleanup history')
        phases=(('before-save',section['beforeSaveFrames']),('after-save',section['afterSaveFrames']))
    else:
        require(cmo3 is None, 'reopen must not save another model')
        before=section['reopened'];verify_cube_persistence_capture(before,input_path,texture=texture,padded=padded)
        root=section['historyBefore'];history(root,root);history(section['historyAfter'],root)
        require(restored_history(root,section['historyAfter']), 'reopen authored history changed')
        phases=(('reopen',section['reopenFrames']),)
    expected={f'cube-persistence-render/{phase}-{i}.png' for phase,_ in phases for i in range(9)}
    require(set(frames) == expected, 'cube persistence PNG inventory')
    for phase,rows in phases:
        progress=section['renderProgress-'+phase]
        require(progress['stage'] == 'complete' and progress['entryState'] == progress['restoredState'] == before['state']
                and restored_history(progress['entryHistory'],progress['restoredHistory']), 'render sweep restoration')
        RENDER['verify_persistence_frames'](rows,before,phase,input_path,frames.__getitem__,texture=texture,padded=padded)
    if mode == 'cube-persistence-save':
        for i in range(9):
            require(RENDER['PNG'](frames[f'cube-persistence-render/before-save-{i}.png']) ==
                    RENDER['PNG'](frames[f'cube-persistence-render/after-save-{i}.png']), 'SAVE_AS changed pixels')
    return dict(mode=mode,forms=12150,frames=len(frames))


def verify_cube_persistence_pair(a, b):
    """Requires complete per-job admission first; enforces the cross-job artifact/process/content binding."""
    import hashlib
    require(a['mode'] == 'cube-persistence-save' and b['mode'] == 'cube-persistence-reopen', 'cube pair modes')
    left,right=a['report'],b['report'];save,reopen=left['cubePersistence'],right['cubePersistence']
    require(save['inputSha256'] == reopen['inputSha256']
            and save['renderUvTransform'] == reopen['renderUvTransform']
            and save.get('textureLayout','FLAT_COLOR_INTERIOR') == reopen.get('textureLayout','FLAT_COLOR_INTERIOR')
            and save.get('registeredImageSize',[256,128]) == reopen.get('registeredImageSize',[256,128]),
            'cube pair source/layout differs')
    require(left['runId'] != right['runId'] and save['process'] != reopen['process'], 'cube reopen requires fresh run/process')
    require(all(a['lifecycle'][k] != b['lifecycle'][k] for k in ('jobId','attemptId','runId')),
            'cube pair job identities reused')
    sha=hashlib.sha256(a['cmo3']).hexdigest()
    checks=b['lifecycle']['details']['postContainmentChecks']
    require(sha == save['artifactSha256'] == checks['fixtureSourceSha256'] == checks['fixtureCopySha256']
            == b['lifecycle']['details']['sourceFixtureBeforeSha256'] == b['lifecycle']['details']['fixtureBeforeSha256'],
            'cube reopen did not load exact saved artifact')
    require(save['beforeSave'] == save['afterSave'] == reopen['reopened'], 'cube reopened content differs')
    for i in range(9):
        require(RENDER['PNG'](a['cubePersistenceFrames'][f'cube-persistence-render/after-save-{i}.png']) ==
                RENDER['PNG'](b['cubePersistenceFrames'][f'cube-persistence-render/reopen-{i}.png']),
                'cube reopened pixels differ')
    return dict(forms=12150,poses=9,artifactSha256=sha)


def verify_cube_atlas_report(report, run_id, input_path, frames, expected_mode="cube-atlas", *, texture=None, padded=False):
    """Require actual atlas inputs plus source-bound native rasters; raw-image previews cannot pass."""
    import copy
    require(type(padded) is bool and padded == (texture is not None), 'explicit atlas source profile')
    width,height,size = (512,256,1024) if padded else (256,128,512)
    expected_input = ('dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859'
                      if padded else CUBE['INPUT_SHA256'])
    require(expected_mode in ('cube-atlas', 'cube-export', 'cube-atlas-save') and report['mi3dMode'] == expected_mode and report['runId'] == run_id
            and report['status'] == 'PASS' and report['failure'] is None
            and report['finallyRestored'] is True and report['finallyCounts'] == report['initialCounts'],
            'atlas terminal/recovery')
    require(report['seed'] == report['document'] and all(isinstance(report['seed'][k],str) and report['seed'][k]
            for k in ('documentId','modelId','editorModelId','kind')), 'atlas must use launcher seed')
    for name in ('ordinaryWrite','evaluation','order','morph','morphOrder','textureBinding','pixelSampling',
                 'shapeImport','wholeImport','cubeRender','cubeGeometry','jointPose','persistenceSave','persistenceReopen','cubePersistence'):
        require(report[name]['status'] == 'NOT_RUN', 'unexpected probe in atlas mode')
    s=report['cubeAtlas']
    require(s['schemaVersion']=='mi3d-cube-atlas-v1' and s['status']=='PASS' and s['failure'] is None
            and s['inputSha256']==expected_input and s['renderStatus']=='CAPTURED'
            and s['finallyRestored'] is True, 'atlas section')
    if padded:
        require(s.get('textureLayout')=='EDGE_PADDED_POT' and s.get('registeredImageSize')==[width,height], 'atlas source layout')
    initial=s['initial'];verify_cube_persistence_capture(initial,input_path,texture=texture,padded=padded)
    require(s['afterRawDisplay']==initial,'raw-image display changed authored mapping')
    require(s['initialAtlases']==s['restoredAtlases']==[] and s['restored']==initial
            and restored_history(s['initialHistory'],s['restoredHistory']), 'atlas seed not restored')
    for key,command in (('baselineDisplay','SHOW_MODEL_BY_RAW_IMAGE'),('atlasDisplay','SHOW_MODEL_BY_TEXTURE_ATLAS'),
                        ('restoredDisplay','SHOW_MODEL_BY_RAW_IMAGE')):
        require(s[key]==dict(command=command,status='EXECUTED'), 'atlas display command missing')
    image=initial['state']['imageGroups'][0]['images'][0]
    plans=[]
    for name in ('first','second'):
        before,after=s[name+'LayoutBefore'],s[name+'LayoutAfter'];plan=s[name+'Requested']
        require(s[name+'Apply']=='APPLIED' and after['plan']==plan
                and before['modelId']==after['modelId'] and before['documentId']==after['documentId']
                and isinstance(after['modelId'],str) and after['modelId']
                and before['items']==after['items']==[[image[0],width,height]], 'atlas issued layout identity')
        require(set(plan)=={'width','height','pages','names','scale','placements'}
                and plan['width']==plan['height']==size and plan['pages']==1 and plan['scale']==1
                and plan['names']==['Turboism Atlas 1'] and len(plan['placements'])==1, 'atlas complete plan')
        p=plan['placements'][0]
        require(len(p)==7 and p[:2]==[image[0],0] and p[4:]==[width,height,False]
                and all(type(v) is int for v in p[1:6]) and 2<=p[2]<=size-width-2 and 2<=p[3]<=size-height-2, 'atlas placement')
        h0,h1=s[name+'HistoryBefore'],s[name+'HistoryAfter'];history(h0,s['initialHistory']);history(h1,h0)
        require(h1['position']==h0['position']+1 and h0['entryIds'][:h0['position']]==h1['entryIds'][:h0['position']],
                'atlas apply must be one native history step')
        plans.append(plan)
    require(s['firstHistoryBefore']['position']==s['initialHistory']['position']+1
            and s['secondHistoryBefore']['position']==s['firstHistoryAfter']['position'], 'atlas creation/apply history sequence')
    require(s['secondLayoutBefore']==s['firstLayoutAfter'] and plans[0]!=plans[1]
            and plans[0]['placements'][0][2]+plans[1]['placements'][0][2]+width==size
            and plans[0]['placements'][0][3]+plans[1]['placements'][0][3]+height==size, 'atlas did not relocate')
    require(s['undoLayout']==s['firstLayoutAfter'] and s['redoLayout']==s['secondLayoutAfter'], 'native layout Undo/Redo')
    second=s['secondHistoryAfter']
    for key,position in (('undoHistory',second['position']-1),('redoHistory',second['position'])):
        history(s[key],second);require(s[key]['position']==position and s[key]['entryIds'][:second['position']]==second['entryIds'][:second['position']], 'atlas Undo/Redo history')
    for key,atlas_key,plan in (('packed','packedAtlases',plans[0]),('repacked','repackedAtlases',plans[1])):
        atlas=s[atlas_key];require(len(atlas)==1 and len(atlas[0])==5 and isinstance(atlas[0][0],str) and atlas[0][0]
            and atlas[0][1:]==['Turboism Atlas 1',size,size,1], 'atlas inventory')
        capture=s[key];normalized=copy.deepcopy(capture)
        require(len(capture['state']['meshes'])==len(capture['snapshots'])==6,'atlas six faces')
        x,y=plan['placements'][0][2:4]
        for i,(mesh,original,normal) in enumerate(zip(capture['state']['meshes'],initial['state']['meshes'],normalized['state']['meshes'])):
            require(mesh['input']==dict(kind='TEXTURE_ATLAS_REGION',meshId=original['id'],imageId='',atlasId=atlas[0][0]),
                    'atlas frame still uses raw model image or another atlas')
            snapshot=capture['snapshots'][i];source_snapshot=initial['snapshots'][i]
            expected_uvs=[[CUBE['f32']((width*CUBE['f32'](u)+x)/size),CUBE['f32']((height*CUBE['f32'](v)+y)/size)]
                          for u,v in source_snapshot['uvs']]
            require([[CUBE['f32'](u),CUBE['f32'](v)] for u,v in snapshot['uvs']]==expected_uvs and mesh['snapshotSha256']==RENDER['fingerprint'](snapshot),
                    'atlas UVs do not match original-image placement transform')
            normalized['snapshots'][i]['uvs']=copy.deepcopy(source_snapshot['uvs'])
            normal['snapshotSha256']=RENDER['fingerprint'](normalized['snapshots'][i])
            normal['input']=copy.deepcopy(original['input'])
        require(normalized==initial,'atlas changed geometry/material/hierarchy/parameters')
    require(s['undoCapture']==s['packed'] and s['redone']==s['repacked'],'atlas binding Undo/Redo')
    phases=(('atlas-baseline','baselineFrames','initial'),('atlas-packed','packedFrames','packed'),
            ('atlas-repacked','repackedFrames','repacked'),('atlas-redone','redoneFrames','redone'))
    require(set(frames)=={f'cube-persistence-render/{phase}-{i}.png' for phase,_,_ in phases for i in range(9)},'atlas PNG inventory')
    for phase,key,capture in phases:
        progress=s['renderProgress-'+phase]
        require(progress['stage']=='complete' and progress['entryState']==progress['restoredState']==s[capture]['state']
                and restored_history(progress['entryHistory'],progress['restoredHistory']), 'atlas render recovery')
        if padded:
            RENDER['verify_persistence_frames'](s[key],s[capture],phase,input_path,frames.__getitem__,texture=texture,padded=True)
        else:
            RENDER['verify_persistence_frames'](s[key],s[capture],phase,input_path,frames.__getitem__)
    for i in range(9):
        baseline=RENDER['PNG'](frames[f'cube-persistence-render/atlas-baseline-{i}.png'])
        for phase,_,_ in phases[1:]:
            require(RENDER['PNG'](frames[f'cube-persistence-render/{phase}-{i}.png'])==baseline,'atlas changed rendered pixels')
    return dict(forms=12150,poses=9,frames=36,atlasLayouts=2)


def verify_cube_export(report, artifacts, *, cube=None, texture=None):
    """Export capture integrity only. Real official Core evaluation is a separate gate."""
    import hashlib
    import struct
    require(report['mi3dMode'] == 'cube-export', 'export mode')
    s = report['cubeExport']; atlas = report['cubeAtlas']
    require(s['schemaVersion'] == 'mi3d-official-export-v1'
            and s['status'] == 'CAPTURED_NOT_CORE_VERIFIED' and s['failure'] is None
            and s['coreVerification'] == 'NOT_RUN', 'export capture status')
    size=1024 if texture is not None else 512
    if texture is not None:
        require(cube is not None and s.get('inputSha256')==atlas['inputSha256']==
                'dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859'
                and s.get('textureLayout')==atlas.get('textureLayout')=='EDGE_PADDED_POT','export source texture profile')
    require(s['settings'] == [512,512,100,False,False,False,False,False], 'explicit export settings')
    require(s['before'] == s['after'] == atlas['redone'], 'export changed authored model')
    history(s['historyBefore'], atlas['initialHistory']); history(s['historyAfter'], atlas['initialHistory'])
    require(s['historyBefore'] == s['historyAfter']
            and s['historyBefore']['position'] == atlas['redoHistory']['position'], 'export changed history')
    require(set(artifacts) == {'official-export/model.moc3','official-export/texture-0.png'}, 'export file inventory')
    moc = artifacts['official-export/model.moc3']
    require(64 <= len(moc) <= 32*1024*1024 and moc[:4] == b'MOC3', 'MOC3 header/size')
    def check(row, path):
        raw = artifacts[path]
        require(row['path'] == path and type(row['bytes']) is int and row['bytes'] == len(raw)
                and row['sha256'] == hashlib.sha256(raw).hexdigest(), 'export artifact identity')
        return raw
    check(s['moc'], 'official-export/model.moc3')
    require(len(s['textures']) == 1, 'export texture count')
    exported_texture = s['textures'][0]
    require(exported_texture['index'] == 0 and exported_texture['width'] == exported_texture['height'] == size, 'export texture index/dimensions')
    png = check(exported_texture, 'official-export/texture-0.png')
    require(0 < len(png) <= 2*1024*1024, 'export PNG budget')
    pixels = RENDER['PNG'](png, size=size)
    require(len(pixels) == size and all(len(row) == size*4 for row in pixels), 'decoded export dimensions')
    argb = b''.join(bytes((row[i+3],row[i],row[i+1],row[i+2])) for row in pixels for i in range(0,len(row),4))
    require(exported_texture['argbSha256'] == hashlib.sha256(argb).hexdigest(), 'export pixel digest')
    placement = atlas['secondRequested']['placements'][0]
    x,y = placement[2:4]
    if texture is not None:
        import runpy
        oracle=runpy.run_path(str(Path(__file__).resolve().parents[1]/'model-import-3d-user-entry/verify-texture-pixels.py'))
        source=oracle['source_image'](cube,texture)
        require(0<=x and 0<=y and x+512<=size and y+256<=size,'source export placement bounds')
        # The source occupies (1,1) inside its padded image; compare every source
        # texel, preserving labels and orientation, not a few gray swatches.
        for sy in range(source.height):
            for sx in range(source.width):
                observed=pixels[y+1+sy][4*(x+1+sx):4*(x+1+sx)+4]
                require(all(abs(a-b)<=2 for a,b in zip(observed,source.getpixel((sx,sy)))),
                        'export atlas differs from source texture')
    else:
        for dy in (32,64,96):
            for dx in (64,128,192):
                require(pixels[y+dy][4*(x+dx):4*(x+dx)+4] == bytes((231,231,231,255)), 'export atlas interior differs from source material')
    return dict(status='EXPORTED_NOT_CORE_VERIFIED', mocBytes=len(moc), textures=1)
