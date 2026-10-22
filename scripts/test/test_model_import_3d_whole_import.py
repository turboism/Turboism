#!/usr/bin/env python3
"""Actual Java importer output -> independent cube/transaction gate, with protocol mutations."""
import copy
import hashlib
import struct
import zlib
import json
import os
from pathlib import Path
import runpy
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PROBE = ROOT / 'validation/model-import-3d-host-probe'
GATE = runpy.run_path(str(PROBE / 'verify-whole-import.py'))


def has_classpath():
    value = os.environ.get('MI3D_WHOLE_TEST_CLASSPATH', '')
    return bool(value) and all(Path(entry.rstrip('*')).exists() for entry in value.split(os.pathsep))


def fixture(produced):
    root = dict(availability='AVAILABLE', generation=1, revision=1, position=1,
                documentBindingId='doc', managerBindingId='manager', entryIds=['existing'])
    initial = dict(model='model', canvas=[1024,1024,512,512,1],
        meshes=[dict(id='existing',snapshotSha256='a'*64,culling=False,visible=True,parent=None,input=None)],
        parameters=[['Existing','Existing','NORMAL',0,-1,0,1,False]], parts=[],rawImages=[],imageGroups=[])
    committed = copy.deepcopy(initial)
    part = produced['partId']; ids = produced['meshIds']
    committed['parts'].append([part,'MI3D Imported Cube',''])
    committed['parameters'] += [[p,p,'NORMAL',0,-30,0,30,False] for p in GATE['CUBE']['PARAMETERS']]
    committed['rawImages'].append(['raw','source color',256,128])
    committed['imageGroups'].append(dict(name='color',images=[['image','source color',256,128]]))
    committed['meshes'] += [dict(id=i,snapshotSha256=GATE['RENDER']['fingerprint'](s),culling=True,visible=True,parent=part,
        input=dict(kind='MODEL_IMAGE',modelId='model',generation=1,meshId=i,imageId='image')) for i,s in zip(ids,produced['written'])]
    after = dict(root,revision=2,position=2,entryIds=['existing','import'])
    return dict(wholeImport=dict(schemaVersion='mi3d-whole-import-v1',status='PASS',failure=None,finallyRestored=True,
        inputSha256=produced['inputSha256'],renderUvTransform=[.5,.25,.5,.25],initialState=initial,initialHistory=root,
        failures=[dict(stage=s,reached=GATE['STAGES'][:i+1],outcome='ROLLED_BACK',diagnosticId='injected',
            beforeHistory=copy.deepcopy(root),afterHistory=copy.deepcopy(root),afterState=copy.deepcopy(initial))
            for i,s in enumerate(GATE['STAGES'])],outcome='COMMITTED',diagnosticId=None,reached=GATE['STAGES'],
        beforeHistory=root,committedHistory=after,undoneHistory=dict(after,revision=3,position=1),
        redoneHistory=dict(after,revision=4),restoredHistory=dict(after,revision=5,position=1),
        committedState=committed,undoneState=copy.deepcopy(initial),redoneState=copy.deepcopy(committed),
        restoredState=copy.deepcopy(initial),partId=part,meshIds=ids,written=produced['written']))


@unittest.skipUnless(os.environ.get('MI3D_WHOLE_CUBE_INPUT')
                     and Path(os.environ['MI3D_WHOLE_CUBE_INPUT']).is_file()
                     and has_classpath(),
                     'set MI3D_WHOLE_CUBE_INPUT to the authorized frozen cube and MI3D_WHOLE_TEST_CLASSPATH to compiled producer classes')
class WholeImportTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.cube = os.environ.get('MI3D_WHOLE_CUBE_INPUT')
        cp = os.environ.get('MI3D_WHOLE_TEST_CLASSPATH')
        with tempfile.TemporaryDirectory() as tmp:
            output = Path(tmp) / 'producer.json'
            result = subprocess.run(['java','-Xmx768m','-Djava.awt.headless=true','-cp',cp,
                'dev.turboism.validation.modelimport3d.host.SurfaceImportTest',cls.cube,str(output)],
                text=True,capture_output=True,timeout=120)
            if result.returncode:
                raise AssertionError(result.stdout+result.stderr)
            cls.produced = json.loads(output.read_text())
        cls.base = fixture(cls.produced)

    def verify(self, report):
        return GATE['verify_whole_import'](report,self.cube)

    def persistence_capture(self):
        state = copy.deepcopy(self.base['wholeImport']['committedState'])
        del state['model']; state['schema'] = 'mi3d-cube-persistence-state-v1'
        state['meshes'].pop(0)
        for mesh in state['meshes']:
            del mesh['input']['modelId']; del mesh['input']['generation']; mesh['input']['atlasId'] = ''
        return dict(state=state,snapshots=copy.deepcopy(self.produced['written']))

    def test_persistence_capture_actual_producer(self):
        self.assertEqual(dict(meshes=6,forms=12150,renderStatus='NOT_EVALUATED'),
            GATE['verify_cube_persistence_capture'](self.persistence_capture(),self.cube))

    def test_persistence_capture_rejects_mutations(self):
        changes = [
            lambda c:c['state'].update(model='runtime-session'),
            lambda c:c['state']['meshes'][0]['input'].update(generation=1),
            lambda c:c['state']['meshes'][0]['input'].update(atlasId='unexpected'),
            lambda c:c['state']['meshes'][0]['input'].update(imageId='wrong'),
            lambda c:c['snapshots'][0]['forms'][0]['positions'][0].__setitem__(0,999),
            lambda c:c['state']['parameters'].pop(),
            lambda c:c['state']['parts'].clear(),
            lambda c:c['state']['meshes'][0].update(culling=False),
        ]
        for i,change in enumerate(changes):
            with self.subTest(i=i):
                capture=self.persistence_capture();change(capture)
                with self.assertRaises(ValueError):GATE['verify_cube_persistence_capture'](capture,self.cube)

    def test_rejects_projected_source_even_with_consistent_snapshot_hash(self):
        capture=self.persistence_capture()
        sources,_=GATE['CUBE']['cube_source'](self.cube)
        for row,snapshot,vertices in zip(capture['state']['meshes'],capture['snapshots'],sources):
            snapshot['positions']=GATE['CUBE']['truth'](vertices,0,0)
            row['snapshotSha256']=GATE['RENDER']['fingerprint'](snapshot)
        with self.assertRaisesRegex(ValueError,'image-space texture source geometry'):
            GATE['verify_cube_persistence_capture'](capture,self.cube)

    def test_persistence_render_pixels_and_consumption(self):
        raster = runpy.run_path(str(ROOT/'scripts/test/test_model_import_3d_cube_render.py'))['raster']
        render = GATE['RENDER']; capture = self.persistence_capture()
        sources,_ = GATE['CUBE']['cube_source'](self.cube)
        def png(rows):
            def chunk(kind,data):
                return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
            return (b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',1024,1024,8,6,0,0,0))
                +chunk(b'IDAT',zlib.compress(b''.join(b'\0'+r for r in rows)))+chunk(b'IEND',b''))
        frames=[]; artifacts={}
        for index,pose in enumerate(render['POSES']):
            state=copy.deepcopy(capture['state'])
            for parameter,value in zip(GATE['CUBE']['PARAMETERS'],pose):
                next(p for p in state['parameters'] if p[0]==parameter)[3]=value
            consumption=[]
            for i,snapshot in enumerate(capture['snapshots']):
                params=dict(updateVersion=2,parameters=[dict(guid=a['guid'],id=a['id'],value=dict(zip(GATE['CUBE']['PARAMETERS'],pose))[a['id']]) for a in snapshot['axes']])
                actual=dict(artMeshId=snapshot['artMeshId'],sourceGuid=snapshot['sourceGuid'],live=params,consumed=params,
                    passEntryTimeMillis=2,calculated=dict(interpolated=True,coordinateType='Canvas',
                    drawOrder=snapshot['forms'][0]['drawOrder'],positions=GATE['CUBE']['truth'](sources[i],*pose)))
                requested=copy.deepcopy(actual);requested['consumed']['updateVersion']=1;requested['passEntryTimeMillis']=1
                consumption.append(dict(requested=requested,actual=actual,attempts=1,elapsedMillis=1))
            name=f'cube-persistence-render/reopen-{index}.png';raw=png(raster(pose,source=True));artifacts[name]=raw
            history=copy.deepcopy(self.base['wholeImport']['initialHistory'])
            frames.append(dict(phase='reopen',index=index,pose=list(pose),artifact=name,artifactBytes=len(raw),
                artifactSha256=hashlib.sha256(raw).hexdigest(),stateBefore=state,stateAfter=state,
                historyBefore=history,historyAfter=history,consumption=consumption,
                evaluationBefore=[copy.deepcopy(c['actual']) for c in consumption],
                evaluationAfter=[copy.deepcopy(c['actual']) for c in consumption]))
        def verify(rows): return render['verify_persistence_frames'](rows,capture,'reopen',self.cube,artifacts.__getitem__)
        self.assertEqual(verify(frames)['frames'],9)
        self.persistence_pair_checks(frames,capture,artifacts)
        self.atlas_checks(frames,capture,artifacts)
        changes=[lambda f:f.update(artifactSha256='0'*64),
            lambda f:f['consumption'][0]['actual']['consumed'].update(updateVersion=1),
            lambda f:f['consumption'][0]['actual']['calculated']['positions'][0].__setitem__(0,999),
            lambda f:f.update(pose=[30,30]),
            lambda f:f['stateAfter']['meshes'][0].update(culling=False),
            lambda f:f['historyAfter'].update(position=999)]
        for i,change in enumerate(changes):
            with self.subTest(i=i):
                rows=copy.deepcopy(frames);change(rows[0])
                with self.assertRaises(ValueError):verify(rows)
        name=frames[0]['artifact'];raw=png(raster((30,30),source=True));artifacts[name]=raw
        frames[0].update(artifactBytes=len(raw),artifactSha256=hashlib.sha256(raw).hexdigest())
        with self.assertRaisesRegex(ValueError,'pixels differ'):verify(frames)

    def atlas_checks(self, frames, capture, artifacts):
        root=copy.deepcopy(self.base['wholeImport']['initialHistory'])
        def h(n): return dict(root,position=n,revision=n,entryIds=['seed','add-atlas','pack','repack'][:n])
        image=capture['state']['imageGroups'][0]['images'][0][0]
        def plan(x,y):
            return dict(width=512,height=512,pages=1,names=['Turboism Atlas 1'],scale=1,
                placements=[[image,0,x,y,256,128,False]])
        first,second=plan(2,2),plan(254,382)
        def layout(p):return dict(documentId='doc',modelId='model',atlasId='atlas',items=[[image,256,128]],plan=p)
        packed=copy.deepcopy(capture);repacked=copy.deepcopy(capture)
        for value,atlas,p in ((packed,'atlas-1',first),(repacked,'atlas-2',second)):
            x,y=p['placements'][0][2:4]
            for m,snapshot in zip(value['state']['meshes'],value['snapshots']):
                m['input']=dict(kind='TEXTURE_ATLAS_REGION',meshId=m['id'],imageId='',atlasId=atlas)
                snapshot['uvs']=[[GATE['CUBE']['f32']((256*u+x)/512),GATE['CUBE']['f32']((128*v+y)/512)] for u,v in snapshot['uvs']]
                m['snapshotSha256']=GATE['RENDER']['fingerprint'](snapshot)
        def display(cmd): return dict(command=cmd,status='EXECUTED')
        section=dict(schemaVersion='mi3d-cube-atlas-v1',status='PASS',failure=None,inputSha256=self.produced['inputSha256'],
            renderStatus='CAPTURED',finallyRestored=True,initial=capture,restored=copy.deepcopy(capture),
            afterRawDisplay=copy.deepcopy(capture),
            initialAtlases=[],restoredAtlases=[],initialHistory=root,restoredHistory=root,
            baselineDisplay=display('SHOW_MODEL_BY_RAW_IMAGE'),restoredDisplay=display('SHOW_MODEL_BY_RAW_IMAGE'),
            atlasDisplay=display('SHOW_MODEL_BY_TEXTURE_ATLAS'),packed=packed,repacked=repacked,redone=copy.deepcopy(repacked),
            undoCapture=copy.deepcopy(packed),packedAtlases=[['atlas-1','Turboism Atlas 1',512,512,1]],
            repackedAtlases=[['atlas-2','Turboism Atlas 1',512,512,1]],undoLayout=layout(first),redoLayout=layout(second),
            undoHistory=dict(h(4),position=3,revision=5),redoHistory=dict(h(4),revision=6))
        section['initialHistory']=section['restoredHistory']=h(1)
        for name,before,after,pos in (('first',layout(plan(0,0)),layout(first),2),('second',layout(first),layout(second),3)):
            section.update({name+'LayoutBefore':before,name+'LayoutAfter':after,name+'Requested':after['plan'],
                name+'Apply':'APPLIED',name+'HistoryBefore':h(pos),name+'HistoryAfter':h(pos+1)})
        pngs={}
        for phase,key,value in (('atlas-baseline','baselineFrames',capture),('atlas-packed','packedFrames',packed),
                ('atlas-repacked','repackedFrames',repacked),('atlas-redone','redoneFrames',repacked)):
            rows=copy.deepcopy(frames)
            for i,row in enumerate(rows):
                raw=artifacts[row['artifact']];row.update(phase=phase,artifact=f'cube-persistence-render/{phase}-{i}.png')
                for state_key in ('stateBefore','stateAfter'):
                    row[state_key]['meshes']=copy.deepcopy(value['state']['meshes'])
                pngs[row['artifact']]=raw
            section[key]=rows
            section['renderProgress-'+phase]=dict(stage='complete',entryState=value['state'],restoredState=value['state'],
                entryHistory=root,restoredHistory=root)
        ident=dict(documentId='doc',modelId='model',editorModelId='native',kind='MODEL')
        report=dict(mi3dMode='cube-atlas',runId='atlas-run',status='PASS',failure=None,finallyRestored=True,
            initialCounts={},finallyCounts={},seed=ident,document=ident,cubeAtlas=section)
        for name in ('ordinaryWrite','evaluation','order','morph','morphOrder','textureBinding','pixelSampling',
                'shapeImport','wholeImport','cubeRender','cubeGeometry','jointPose','persistenceSave','persistenceReopen','cubePersistence'):
            report[name]=dict(status='NOT_RUN')
        def verify(r): return GATE['verify_cube_atlas_report'](r,'atlas-run',self.cube,pngs)
        self.assertEqual(verify(report)['frames'],36)
        self.export_checks(report)
        for change in (lambda s:s.update(atlasDisplay=display('SHOW_MODEL_BY_RAW_IMAGE')),
                lambda s:s['packed']['state']['meshes'][0].update(input=capture['state']['meshes'][0]['input']),
                lambda s:s.update(undoLayout=s['redoLayout']),
                lambda s:s['restoredAtlases'].append(['unremoved']),
                lambda s:s.update(secondApply='NO_CHANGE'),
                lambda s:s['repacked']['state']['meshes'][0].update(snapshotSha256='0'*64),
                lambda s:s['packed']['snapshots'][0].update(uvs=copy.deepcopy(capture['snapshots'][0]['uvs'])),
                lambda s:s['secondHistoryAfter'].update(position=10)):
            altered=copy.deepcopy(report);change(altered['cubeAtlas'])
            with self.assertRaises(ValueError):verify(altered)

    def export_checks(self, atlas_report):
        report=copy.deepcopy(atlas_report);report['mi3dMode']='cube-export'
        atlas=report['cubeAtlas'];moc=b'MOC3'+bytes(60)
        def png(color):
            def chunk(kind,data):
                return struct.pack('>I',len(data))+kind+data+struct.pack('>I',zlib.crc32(kind+data)&0xffffffff)
            return (b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',512,512,8,6,0,0,0))
                +chunk(b'IDAT',zlib.compress((b'\0'+bytes(color)*512)*512))+chunk(b'IEND',b''))
        raw=png((231,231,231,255))
        def row(path,data):return dict(path=path,bytes=len(data),sha256=hashlib.sha256(data).hexdigest())
        artifacts={'official-export/model.moc3':moc,'official-export/texture-0.png':raw}
        report['cubeExport']=dict(schemaVersion='mi3d-official-export-v1',status='CAPTURED_NOT_CORE_VERIFIED',
            failure=None,coreVerification='NOT_RUN',settings=[512,512,100,False,False,False,False,False],
            before=copy.deepcopy(atlas['redone']),after=copy.deepcopy(atlas['redone']),
            historyBefore=copy.deepcopy(atlas['redoHistory']),historyAfter=copy.deepcopy(atlas['redoHistory']),
            moc=row('official-export/model.moc3',moc),textures=[dict(row('official-export/texture-0.png',raw),
                index=0,width=512,height=512,argbSha256=hashlib.sha256(bytes((255,231,231,231))*512*512).hexdigest())])
        def verify(r,a): return GATE['verify_cube_export'](r,a)
        self.assertEqual(verify(report,artifacts)['status'],'EXPORTED_NOT_CORE_VERIFIED')
        for change in (lambda s:s['settings'].__setitem__(2,1),
                lambda s:s['after']['state']['meshes'][0].update(visible=False),
                lambda s:s['historyAfter'].update(position=99),
                lambda s:s['textures'][0].update(argbSha256='0'*64),
                lambda s:s['moc'].update(sha256='0'*64),
                lambda s:s.update(coreVerification='PASS')):
            altered=copy.deepcopy(report);change(altered['cubeExport'])
            with self.assertRaises(ValueError):verify(altered,artifacts)
        with self.assertRaises(ValueError):verify(report,dict(artifacts,extra=b'bad'))
        altered=copy.deepcopy(report);bad=png((1,2,3,255));changed=dict(artifacts);changed['official-export/texture-0.png']=bad
        altered['cubeExport']['textures'][0].update(row('official-export/texture-0.png',bad),
            argbSha256=hashlib.sha256(bytes((255,1,2,3))*512*512).hexdigest())
        with self.assertRaisesRegex(ValueError,'source material'):verify(altered,changed)

    def persistence_pair_checks(self, frames, capture, artifacts):
        root=copy.deepcopy(self.base['wholeImport']['initialHistory'])
        initial=copy.deepcopy(self.base['wholeImport']['initialState']);initial['meshes']=[]
        imported=dict(root,position=2,revision=2,entryIds=['canvas','import'])
        common=dict(schemaVersion='mi3d-cube-persistence-v1',status='PASS',failure=None,
            inputSha256=self.produced['inputSha256'],renderUvTransform=[.5,.25,.5,.25],renderStatus='CAPTURED',observedImageBytes='UNAVAILABLE')
        cmo3=b'synthetic file bytes, not native CMO3';sha=hashlib.sha256(cmo3).hexdigest()
        def phase(name):
            rows=copy.deepcopy(frames);out={}
            for i,row in enumerate(rows):
                raw=artifacts[row['artifact']];row.update(phase=name,artifact=f'cube-persistence-render/{name}-{i}.png')
                out[row['artifact']]=raw
            return rows,out
        pre,pre_png=phase('before-save');post,post_png=phase('after-save')
        save=dict(common,mode='cube-persistence-save',process=dict(pid=10,startEpochMillis=100),
            beforeSave=capture,afterSave=copy.deepcopy(capture),beforeSaveFrames=pre,afterSaveFrames=post,
            rawDisplayStatus='EXECUTED',afterRawDisplay=copy.deepcopy(capture),
            meshIds=self.produced['meshIds'],partId=self.produced['partId'],initialState=initial,restoredState=copy.deepcopy(initial),
            initialHistory=root,importHistoryBefore=root,importHistoryAfter=imported,restoredHistory=root,finallyRestored=True,
            artifact='persistence/mi3d-cube-model.cmo3',artifactBytes=len(cmo3),artifactSha256=sha)
        reopen=dict(common,mode='cube-persistence-reopen',process=dict(pid=11,startEpochMillis=200),
            reopened=copy.deepcopy(capture),reopenFrames=copy.deepcopy(frames),historyBefore=root,historyAfter=root)
        def report(section,run):
            ident=dict(documentId=run,modelId=run,editorModelId=run,kind='MODEL')
            value=dict(runId=run,status='PASS',failure=None,mi3dMode=section['mode'],cubePersistence=section,
                finallyRestored=True,initialCounts={},finallyCounts={},document=ident,
                seed=ident if section['mode'].endswith('reopen') else dict(ident,documentId='seed',modelId='seed',editorModelId='seed'))
            for name in ('ordinaryWrite','evaluation','order','morph','morphOrder','textureBinding','pixelSampling',
                'shapeImport','wholeImport','cubeRender','cubeGeometry','jointPose','persistenceSave','persistenceReopen'):
                value[name]=dict(status='NOT_RUN')
            return value
        for section,phases in ((save,('before-save','after-save')),(reopen,('reopen',))):
            for phase_name in phases:
                section['renderProgress-'+phase_name]=dict(stage='complete',entryState=capture['state'],restoredState=copy.deepcopy(capture['state']),
                    entryHistory=root,restoredHistory=dict(root,revision=3,entryIds=root['entryIds']+['pose-redo']))
        a_report=report(save,'a');b_report=report(reopen,'b');a_png=dict(pre_png,**post_png)
        for r,png,data in ((a_report,a_png,cmo3),(b_report,artifacts,None)):
            GATE['verify_cube_persistence_report'](r,r['runId'],r['mi3dMode'],self.cube,png,data)
        a=dict(mode=save['mode'],report=a_report,cmo3=cmo3,cubePersistenceFrames=a_png,lifecycle=dict(jobId='a',attemptId='a',runId='a'))
        details=dict(sourceFixtureBeforeSha256=sha,fixtureBeforeSha256=sha,
            postContainmentChecks=dict(fixtureSourceSha256=sha,fixtureCopySha256=sha))
        b=dict(mode=reopen['mode'],report=b_report,cubePersistenceFrames=artifacts,lifecycle=dict(jobId='b',attemptId='b',runId='b',details=details))
        self.assertEqual(GATE['verify_cube_persistence_pair'](a,b)['poses'],9)
        for change in (lambda v:v['report']['cubePersistence'].update(process=save['process']),
                       lambda v:v['lifecycle'].update(jobId='a'),
                       lambda v:v['lifecycle']['details']['postContainmentChecks'].update(fixtureCopySha256='0'*64),
                       lambda v:v['report']['cubePersistence']['reopened']['state']['parts'].clear()):
            mutated=copy.deepcopy(b);change(mutated)
            with self.assertRaises(ValueError):GATE['verify_cube_persistence_pair'](a,mutated)
        for change in (lambda r:r['cubePersistence'].update(artifactSha256='0'*64),
                       lambda r:r.update(finallyRestored=False),
                       lambda r:r.update(document=r['seed']),
                       lambda r:r['wholeImport'].update(status='PASS'),
                       lambda r:r['cubePersistence']['renderProgress-before-save']['restoredHistory'].update(position=2),
                       lambda r:r['cubePersistence']['renderProgress-before-save']['restoredHistory']['entryIds'].__setitem__(0,'foreign')):
            mutated=copy.deepcopy(a_report);change(mutated)
            with self.assertRaises(ValueError):GATE['verify_cube_persistence_report'](mutated,'a',save['mode'],self.cube,a_png,cmo3)

    def test_actual_importer_stored_geometry_and_complete_protocol(self):
        self.assertEqual(dict(meshes=6,forms=12150,failureStages=6),self.verify(self.base))

    def test_rejects_transaction_and_restore_mutations(self):
        changes = [
            lambda s:s['failures'].pop(),
            lambda s:s['failures'][2].update(reached=['AXES']),
            lambda s:s['failures'][4].update(outcome='RECOVERY_FAILED'),
            lambda s:s['failures'][0]['afterState']['parameters'].pop(),
            lambda s:s['failures'][3]['afterHistory'].update(revision=2),
            lambda s:s['committedHistory'].update(position=3),
            lambda s:s['undoneHistory'].update(position=2),
            lambda s:s['restoredState']['meshes'][0].update(snapshotSha256='b'*64),
            lambda s:s['redoneState']['meshes'][0].update(visible=False),
            lambda s:s.update(finallyRestored=False),
        ]
        for change in changes:
            with self.subTest(change=changes.index(change)):
                value=copy.deepcopy(self.base);change(value['wholeImport'])
                with self.assertRaises(ValueError):self.verify(value)

    def test_rejects_geometry_uv_parent_and_material_mutations(self):
        changes = [
            lambda s:s['written'][0]['forms'][0]['positions'][0].__setitem__(0,999),
            lambda s:s['written'][1]['uvs'][0].__setitem__(1,0),
            lambda s:s['written'][2]['cells'].pop(),
            lambda s:s['written'][3]['axes'][0]['keys'].pop(),
            lambda s:s['committedState']['meshes'][1].update(parent='foreign'),
            lambda s:s['committedState']['meshes'][2].update(culling=False),
            lambda s:s['committedState']['meshes'][3]['input'].update(imageId='foreign'),
            lambda s:s['committedState']['rawImages'].append(['extra','extra',256,128]),
        ]
        for change in changes:
            with self.subTest(change=changes.index(change)):
                value=copy.deepcopy(self.base);section=value['wholeImport'];change(section)
                section['redoneState']=copy.deepcopy(section['committedState'])
                with self.assertRaises(ValueError):self.verify(value)

if __name__ == '__main__':
    unittest.main()
