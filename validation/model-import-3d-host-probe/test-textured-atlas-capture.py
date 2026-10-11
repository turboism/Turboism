"""Synthetic atlas metadata with actual source import forms; no native atlas claim."""
import copy
import hashlib
from pathlib import Path
import runpy
import struct
import unittest
from unittest.mock import Mock,patch

HERE=Path(__file__).resolve().parent
CAPTURE=runpy.run_path(str(HERE/'test-textured-persistence-capture.py'))
M=runpy.run_path(str(HERE/'verify-atlas-persistence.py'))

class TexturedAtlasCaptureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        source=CAPTURE['TexturedPersistenceCaptureTests'];source.setUpClass();cls.raw=source.base

    def packed(self,x=2,y=2):
        raw=self.raw;image=raw['state']['imageGroups'][0]['images'][0][0];cube=copy.deepcopy(raw)
        for mesh,snapshot in zip(cube['state']['meshes'],cube['snapshots']):
            snapshot['uvs']=[[M['C']['f32']((512*M['C']['f32'](u)+x)/1024),M['C']['f32']((256*M['C']['f32'](v)+y)/1024)] for u,v in snapshot['uvs']]
            mesh['snapshotSha256']=M['R']['fingerprint'](snapshot)
            mesh['input']=dict(kind='TEXTURE_ATLAS_REGION',meshId=mesh['id'],imageId='',atlasId='atlas-guid')
        return dict(cube=cube,atlas=['atlas-guid','Turboism Atlas 1',1024,1024,1],
            layout=dict(atlasId='Turboism Atlas 1',items=[[image,512,256]],
                plan=dict(width=1024,height=1024,pages=1,names=['Turboism Atlas 1'],scale=1,
                    placements=[[image,0,x,y,512,256,False]])))

    def verify(self,value):
        return M['packed'](value,self.raw,CAPTURE['CUBE'],texture=CAPTURE['TEXTURE'],padded=True)

    def test_both_extreme_placements_preserve_source(self):
        for x,y in ((2,2),(510,766)):
            self.assertEqual(self.verify(self.packed(x,y))['forms'],12150)

    def test_old_or_implicit_layout_rejected(self):
        with self.assertRaises(ValueError):M['packed'](self.packed(),self.raw,CAPTURE['CUBE'])
        c=self.packed();c['atlas'][2:4]=[512,512]
        with self.assertRaises(ValueError):self.verify(c)

    def test_rejects_scaling_rotation_margins_and_identity(self):
        for change in (
            lambda c:c['layout']['plan'].update(scale=.5),
            lambda c:c['layout']['plan']['placements'][0].__setitem__(6,True),
            lambda c:c['layout']['plan']['placements'][0].__setitem__(2,511),
            lambda c:c['layout']['plan']['placements'][0].__setitem__(3,767),
            lambda c:c['layout'].update(atlasId='atlas-guid'),
            lambda c:c['cube']['state']['meshes'][0]['input'].update(atlasId='Turboism Atlas 1'),
        ):
            c=self.packed();change(c)
            with self.assertRaises(ValueError):self.verify(c)

    def test_one_ulp_uv_rejected_after_rehash(self):
        c=self.packed();s=c['cube']['snapshots'][0];u=s['uvs'][0][0]
        bits=struct.unpack('<I',struct.pack('<f',u))[0]
        s['uvs'][0][0]=struct.unpack('<f',struct.pack('<I',bits+1))[0]
        c['cube']['state']['meshes'][0]['snapshotSha256']=M['R']['fingerprint'](s)
        with self.assertRaisesRegex(ValueError,'source-to-atlas UV'):self.verify(c)

    def test_source_pair_identity_and_frame_forwarding(self):
        c=self.packed();payload=b'synthetic file, not a native CMO3';sha=hashlib.sha256(payload).hexdigest()
        h=dict(availability='AVAILABLE',generation=1,revision=1,position=1,
            documentBindingId='doc',managerBindingId='manager',entryIds=['import'])
        common=dict(schemaVersion='mi3d-cube-atlas-persistence-v1',status='PASS',failure=None,
            renderStatus='CAPTURED',inputSha256='dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859',
            textureLayout='EDGE_PADDED_POT',registeredImageSize=[512,256],historyBefore=h,historyAfter=h)
        a=dict(common,mode='atlas-save',process=dict(pid=1,startEpochMillis=1),beforeSave=c,afterSave=c,
            artifact='persistence/mi3d-cube-model.cmo3',artifactBytes=len(payload),artifactSha256=sha)
        b=dict(common,mode='atlas-reopen',process=dict(pid=2,startEpochMillis=2),reopened=c,afterDisplay=c,afterObservation=c)
        af={};bf={}
        for section,phase,key,frames in ((a,'atlas-before-save','beforeSaveFrames',af),
                                        (a,'atlas-after-save','afterSaveFrames',af),(b,'atlas-reopen','reopenFrames',bf)):
            section[key]=[]
            section['renderProgress-'+phase]=dict(stage='complete',entryState=c['cube']['state'],restoredState=c['cube']['state'],
                entryHistory=h,restoredHistory=h)
            frames.update({f'cube-persistence-render/{phase}-{i}.png':b'pixel-placeholder' for i in range(9)})
        def verify(right=b,source_sha=sha,**kwargs):
            return M['pair'](a,right,payload,source_sha,sha,self.raw,CAPTURE['CUBE'],af,bf,**kwargs)
        # Only pair protocol/forwarding: native CMO3/raster/lifecycle are not simulated acceptance.
        checker=Mock()
        with patch.dict(M['R'],verify_persistence_frames=checker,PNG=lambda data:data):
            self.assertEqual(verify(texture=CAPTURE['TEXTURE'],padded=True)['poses'],9)
            self.assertEqual(checker.call_count,3)
            for call in checker.call_args_list:
                self.assertEqual(call.kwargs,dict(texture=CAPTURE['TEXTURE'],padded=True))
            for key,value in [('process',a['process']),('inputSha256',M['C']['INPUT_SHA256']),
                              ('textureLayout','ORIGINAL'),('registeredImageSize',[256,128])]:
                right=copy.deepcopy(b);right[key]=value
                with self.assertRaises(ValueError):verify(right,texture=CAPTURE['TEXTURE'],padded=True)
            with self.assertRaisesRegex(ValueError,'exact saved file'):
                verify(source_sha='0'*64,texture=CAPTURE['TEXTURE'],padded=True)
            with self.assertRaises(ValueError):verify()
            ident=dict(documentId='d',modelId='m',editorModelId='e',kind='MODEL')
            report=dict(mi3dMode='cube-atlas-reopen',runId='reopen-source',status='PASS',failure=None,
                finallyRestored=True,initialCounts={},finallyCounts={},seed=ident,document=ident,
                cubeAtlas={'status':'NOT_RUN'},cubeAtlasPersistence=b)
            for name in ('ordinaryWrite','evaluation','order','morph','morphOrder','textureBinding','pixelSampling',
                         'shapeImport','wholeImport','cubeRender','cubeGeometry','jointPose','persistenceSave',
                         'persistenceReopen','cubePersistence','cubeExport'):
                report[name]=dict(status='NOT_RUN')
            self.assertEqual(M['report_leg'](report,'reopen-source','cube-atlas-reopen',CAPTURE['CUBE'],bf,
                raw_reference=self.raw,texture=CAPTURE['TEXTURE'],padded=True)['frames'],9)
            self.assertEqual(checker.call_args.kwargs,dict(texture=CAPTURE['TEXTURE'],padded=True))

    def test_complete_source_atlas_report(self):
        image=self.raw['state']['imageGroups'][0]['images'][0][0]
        def h(position):return dict(availability='AVAILABLE',generation=1,revision=position,position=position,
            documentBindingId='d',managerBindingId='m',entryIds=['seed','atlas','first','second'][:position])
        def layout(p):return dict(p,documentId='d',modelId='m')
        first=self.packed();second=self.packed(510,766)
        display=lambda command:dict(command=command,status='EXECUTED')
        s=dict(schemaVersion='mi3d-cube-atlas-v1',status='PASS',failure=None,
            inputSha256='dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859',
            textureLayout='EDGE_PADDED_POT',registeredImageSize=[512,256],renderStatus='CAPTURED',finallyRestored=True,
            initial=self.raw,restored=self.raw,afterRawDisplay=self.raw,initialAtlases=[],restoredAtlases=[],
            initialHistory=h(1),restoredHistory=h(1),baselineDisplay=display('SHOW_MODEL_BY_RAW_IMAGE'),
            restoredDisplay=display('SHOW_MODEL_BY_RAW_IMAGE'),atlasDisplay=display('SHOW_MODEL_BY_TEXTURE_ATLAS'),
            packed=first['cube'],repacked=second['cube'],redone=second['cube'],undoCapture=first['cube'],
            packedAtlases=[first['atlas']],repackedAtlases=[second['atlas']],undoLayout=layout(first['layout']),
            redoLayout=layout(second['layout']),undoHistory=dict(h(4),position=3),redoHistory=h(4))
        for name,before,after,pos in [('first',first['layout'],first['layout'],2),('second',first['layout'],second['layout'],3)]:
            s.update({name+'LayoutBefore':layout(before),name+'LayoutAfter':layout(after),name+'Requested':after['plan'],
                      name+'Apply':'APPLIED',name+'HistoryBefore':h(pos),name+'HistoryAfter':h(pos+1)})
        frames={}
        for phase,key,c in [('atlas-baseline','baselineFrames',self.raw),('atlas-packed','packedFrames',first['cube']),
                            ('atlas-repacked','repackedFrames',second['cube']),('atlas-redone','redoneFrames',second['cube'])]:
            s[key]=[];s['renderProgress-'+phase]=dict(stage='complete',entryState=c['state'],restoredState=c['state'],
                entryHistory=h(1),restoredHistory=h(1))
            frames.update({f'cube-persistence-render/{phase}-{i}.png':b'pixel-placeholder' for i in range(9)})
        ident=dict(documentId='d',modelId='m',editorModelId='e',kind='MODEL')
        report=dict(mi3dMode='cube-atlas',runId='atlas-test',status='PASS',failure=None,finallyRestored=True,
            initialCounts={},finallyCounts={},seed=ident,document=ident,cubeAtlas=s)
        for name in ('ordinaryWrite','evaluation','order','morph','morphOrder','textureBinding','pixelSampling',
                     'shapeImport','wholeImport','cubeRender','cubeGeometry','jointPose','persistenceSave','persistenceReopen','cubePersistence'):
            report[name]=dict(status='NOT_RUN')
        def verify(r):return M['W']['verify_cube_atlas_report'](r,'atlas-test',CAPTURE['CUBE'],frames,
            texture=CAPTURE['TEXTURE'],padded=True)
        checker=Mock()
        with patch.dict(M['W']['RENDER'],verify_persistence_frames=checker,PNG=lambda data:data):
            self.assertEqual(verify(report)['frames'],36)
            self.assertEqual(checker.call_count,4)
            for call in checker.call_args_list:self.assertEqual(call.kwargs,dict(texture=CAPTURE['TEXTURE'],padded=True))
            for mutate in (
                lambda c:c.update(textureLayout='ORIGINAL'),
                lambda c:c.update(registeredImageSize=[256,128]),
                lambda c:c['secondRequested']['placements'][0].__setitem__(2,254),
                lambda c:c.update(undoLayout=c['redoLayout']),
            ):
                r=copy.deepcopy(report);mutate(r['cubeAtlas'])
                with self.assertRaises(ValueError):verify(r)

if __name__=='__main__':unittest.main()
