"""Fixed plane/seam profile checks against actual synthetic importer capture reports."""
import json,runpy,unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
API=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-surface-geometry.py'))
class PlanarCaptureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.inputs={}
        for name in ('plane','plane-seam'):
            raw=(ROOT/f'artifacts/plane-textured/{name}.glb').read_bytes()
            report=json.loads((ROOT/f'build/model-import-3d-offline/planar-capture-oracle-r1-20261001/{name}-success.json').read_text())
            cls.inputs[name]=(raw,report)
    def test_both_profiles(self):
        for raw,r in self.inputs.values():
            result=API['verify_planar'](r,raw)
            self.assertEqual(result['samples'],5841)
            self.assertLess(result['maxErrorPixels'],.1)
    def test_outer_protocol(self):
        # Manufactured lifecycle metadata tests the gate, never proves host execution.
        for name,(raw,capture) in self.inputs.items():
            mode=name+'-geometry'
            r=dict(schemaVersion='mi3d-native-n2-v1',scope='N2_BOOTSTRAP_CREATE_GRID_READ',
                mi3dMode=mode,runId='synthetic-run',status='PASS',failure=None,
                stageAStatus='NOT_EVALUATED',requestedVersion='5302',hostIdentity=None,
                checks=[dict(id=i,status='PASS',actual=True,evidenceKind='EXACT_HOST') for i in ('newCommand','newIdentity')],
                initialCounts=dict(meshes=0,deformers=0,parameters=0,parts=0),finallyRestored=True)
            r['finallyCounts']=dict(r['initialCounts'])
            for phase,key in (('SEED','seed'),('NEW','document')):
                r[key]=dict(kind='MODEL',documentId=key+'-doc',modelId=key+'-model',editorModelId=key+'-editor')
                r['documentWait'+phase]=dict(phase=phase,reason='READY',attempts=1,documentPresent=True,
                    modelPresent=True,kind='MODEL',documentId=r[key]['documentId'],snapshotModelId=r[key]['modelId'],editorModelId=r[key]['editorModelId'])
            section=dict(profile='PLANE' if name=='plane' else 'SEAM',status='PASS',
                inputSha256=API['PLANE_SHA'] if name=='plane' else API['SEAM_SHA'],importOutcome='COMMITTED',
                finallyRestored=True,initialState={'synthetic':True},restoredState={'synthetic':True},capture=capture)
            r['planarGeometry']=section
            check=lambda:API['verify_planar_report'](r,'synthetic-run',raw,expected_mode=mode)
            self.assertEqual(check()['samples'],5841)
            for target,key,value in ((r,'runId','wrong'),(r,'mi3dMode','sheet-geometry'),
                    (section,'profile','WRONG'),(section,'inputSha256','wrong'),
                    (section,'restoredState',{}),(r,'finallyRestored',False),
                    (r['document'],'documentId',r['seed']['documentId'])):
                old=target[key]
                try:
                    target[key]=value
                    with self.assertRaises(ValueError):check()
                finally:target[key]=old

    def test_source_profile_substitution(self):
        with self.assertRaises(ValueError):API['verify_planar'](self.inputs['plane'][1],self.inputs['plane-seam'][0])
    def test_vertex_correspondence(self):
        for raw,r in self.inputs.values():
            detail=r['samples'][0]['meshes'][0]
            for key in ('actual','lastObservation'):
                p=detail[key]['calculated']['positions'];p[0],p[1]=p[1],p[0]
            try:
                with self.assertRaisesRegex(ValueError,'projection'):API['verify_planar'](r,raw)
            finally:
                for key in ('actual','lastObservation'):
                    p=detail[key]['calculated']['positions'];p[0],p[1]=p[1],p[0]
    def test_seam_merge(self):
        raw,r=self.inputs['plane-seam'];before,after=r['before'],r['after']
        try:
            r['before']=before[:1];r['after']=after[:1]
            with self.assertRaisesRegex(ValueError,'mesh identities'):API['verify_planar'](r,raw)
        finally:r['before']=before;r['after']=after
if __name__=='__main__':unittest.main()
