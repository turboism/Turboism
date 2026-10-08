"""Frame protocol/routing with stub reference pixels; not a raster or host acceptance."""
import copy
import hashlib
import io
from pathlib import Path
import runpy
import unittest
from unittest.mock import patch
from unittest.mock import Mock
from PIL import Image

HERE=Path(__file__).resolve().parent
CAPTURE=runpy.run_path(str(HERE/'test-textured-persistence-capture.py'))
M=runpy.run_path(str(HERE/'verify-cube-render.py'))
PIXELS=runpy.run_path(str(HERE.parent/'model-import-3d-user-entry/verify-texture-pixels.py'))

class TexturedPersistenceFramesTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        C=CAPTURE['TexturedPersistenceCaptureTests'];C.setUpClass();cls.capture=C.base
        cls.frames=[];cls.artifacts={}
        sources,_=M['GEOMETRY']['cube_source'](CAPTURE['CUBE'])
        image=Image.new('RGBA',(1024,1024),(0,0,0,0));image.putpixel((2,2),(200,40,20,255))
        output=io.BytesIO();image.save(output,format='PNG');raw=output.getvalue()
        for index,pose in enumerate(M['POSES']):
            state=copy.deepcopy(cls.capture['state'])
            for parameter,value in zip(M['GEOMETRY']['PARAMETERS'],pose):
                next(p for p in state['parameters'] if p[0]==parameter)[3]=value
            consumption=[]
            for i,snapshot in enumerate(cls.capture['snapshots']):
                params=dict(updateVersion=2,parameters=[dict(guid=a['guid'],id=a['id'],value=dict(zip(M['GEOMETRY']['PARAMETERS'],pose))[a['id']]) for a in snapshot['axes']])
                actual=dict(artMeshId=snapshot['artMeshId'],sourceGuid=snapshot['sourceGuid'],live=params,consumed=copy.deepcopy(params),
                    passEntryTimeMillis=2,calculated=dict(interpolated=True,coordinateType='Canvas',
                    drawOrder=snapshot['forms'][0]['drawOrder'],positions=M['GEOMETRY']['truth'](sources[i],*pose)))
                requested=copy.deepcopy(actual);requested['consumed']['updateVersion']=1;requested['passEntryTimeMillis']=1
                consumption.append(dict(requested=requested,actual=actual,attempts=1,elapsedMillis=1))
            name=f'cube-persistence-render/reopen-{index}.png';cls.artifacts[name]=raw
            history=dict(availability='AVAILABLE',revision=1,position=1,entryIds=['import'])
            cls.frames.append(dict(phase='reopen',index=index,pose=list(pose),artifact=name,artifactBytes=len(raw),
                artifactSha256=hashlib.sha256(raw).hexdigest(),stateBefore=state,stateAfter=copy.deepcopy(state),
                historyBefore=history,historyAfter=copy.deepcopy(history),consumption=consumption,
                evaluationBefore=[copy.deepcopy(c['actual']) for c in consumption],evaluationAfter=[copy.deepcopy(c['actual']) for c in consumption]))

    def verify(self,frames,wrong=False,phase='reopen',artifacts=None):
        seen=[]
        def reference(cube,glb,pose,*,persistence=False):
            self.assertEqual((cube,glb),(CAPTURE['CUBE'],CAPTURE['TEXTURE']))
            self.assertIs(persistence,True);seen.append(tuple(pose))
            return [(2,2,(0 if wrong else 200,40,20,255))],[],1
        module=dict(PIXELS,reference=reference)
        with patch.object(M['runpy'],'run_path',return_value=module):
            result=M['verify_persistence_frames'](frames,self.capture,phase,CAPTURE['CUBE'],
                (self.artifacts if artifacts is None else artifacts).__getitem__,texture=CAPTURE['TEXTURE'],padded=True)
        self.assertEqual(seen,list(M['POSES']));return result

    def test_routes_all_frames_through_source_reference(self):
        self.assertEqual(self.verify(self.frames),dict(frames=9,samples=9))

    def test_source_pixel_mismatch_rejected(self):
        with self.assertRaisesRegex(ValueError,'persistence pixels differ'):self.verify(self.frames,wrong=True)

    def test_atlas_persistence_phase_dispatch(self):
        for phase in ('atlas-before-save','atlas-after-save','atlas-reopen'):
            frames=copy.deepcopy(self.frames);artifacts={}
            for i,frame in enumerate(frames):
                raw=self.artifacts[frame['artifact']]
                frame.update(phase=phase,artifact=f'cube-persistence-render/{phase}-{i}.png')
                artifacts[frame['artifact']]=raw
            self.assertEqual(self.verify(frames,phase=phase,artifacts=artifacts)['frames'],9)
        for phase in ('atlas-save','../reopen',''):
            with self.assertRaisesRegex(ValueError,'persistence frame inventory'):
                self.verify(self.frames,phase=phase)

    def test_state_consumption_and_identity_rejections(self):
        for mutate in (
            lambda f:f[0].update(artifactSha256='0'*64),
            lambda f:f[0]['evaluationAfter'][0]['calculated']['positions'][0].__setitem__(0,999),
            lambda f:f[0]['consumption'][0]['actual']['consumed'].update(updateVersion=1),
            lambda f:f[0]['stateAfter']['rawImages'][0].__setitem__(2,256),
        ):
            frames=copy.deepcopy(self.frames);mutate(frames)
            with self.assertRaises(ValueError):self.verify(frames)

    def test_texture_requires_explicit_layout(self):
        with self.assertRaises(ValueError):
            M['verify_persistence_frames'](self.frames,self.capture,'reopen',CAPTURE['CUBE'],self.artifacts.__getitem__,texture=CAPTURE['TEXTURE'])

    def test_reopen_report_forwards_explicit_profile(self):
        gate=CAPTURE['GATE']
        history=dict(availability='AVAILABLE',generation=1,revision=1,position=1,
            documentBindingId='doc',managerBindingId='manager',entryIds=['seed'])
        section=dict(schemaVersion='mi3d-cube-persistence-v1',mode='cube-persistence-reopen',status='PASS',failure=None,
            inputSha256='dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859',
            renderUvTransform=[.5,1/512,.5,1/256],textureLayout='EDGE_PADDED_POT',registeredImageSize=[512,256],
            renderStatus='CAPTURED',observedImageBytes='UNAVAILABLE',process=dict(pid=2,startEpochMillis=3),
            reopened=self.capture,historyBefore=history,historyAfter=copy.deepcopy(history),reopenFrames=self.frames)
        section['renderProgress-reopen']=dict(stage='complete',entryState=self.capture['state'],restoredState=self.capture['state'],
            entryHistory=history,restoredHistory=copy.deepcopy(history))
        identity=dict(documentId='d',modelId='m',editorModelId='e',kind='MODEL')
        report=dict(mi3dMode=section['mode'],runId='reopen-test',status='PASS',failure=None,finallyRestored=True,
            finallyCounts={},initialCounts={},seed=identity,document=identity,cubePersistence=section)
        for name in ('ordinaryWrite','evaluation','order','morph','morphOrder','textureBinding','pixelSampling',
                     'shapeImport','wholeImport','cubeRender','cubeGeometry','jointPose','persistenceSave','persistenceReopen'):
            report[name]=dict(status='NOT_RUN')
        def verify(value,**kwargs):
            return gate['verify_cube_persistence_report'](value,'reopen-test','cube-persistence-reopen',CAPTURE['CUBE'],self.artifacts,**kwargs)
        # Routing check only: actual frame gate has its own tests above.
        frame_gate=Mock(return_value=dict(frames=9))
        with patch.dict(gate['RENDER'],verify_persistence_frames=frame_gate):
            self.assertEqual(verify(report,texture=CAPTURE['TEXTURE'],padded=True)['frames'],9)
            self.assertEqual(frame_gate.call_args.kwargs,dict(texture=CAPTURE['TEXTURE'],padded=True))
            for key,value in [('textureLayout','ORIGINAL'),('registeredImageSize',[256,128]),
                              ('renderUvTransform',[.5,.25,.5,.25]),('inputSha256','0'*64)]:
                mutated=copy.deepcopy(report);mutated['cubePersistence'][key]=value
                with self.assertRaises(ValueError):verify(mutated,texture=CAPTURE['TEXTURE'],padded=True)
            with self.assertRaises(ValueError):verify(report)

if __name__=='__main__':unittest.main()
