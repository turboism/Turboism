import json
from pathlib import Path
import runpy
import unittest

ROOT = Path(__file__).resolve().parents[3]
API = runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-surface-geometry.py'))

class CaptureOracleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.report = json.loads((ROOT/'build/model-import-3d-offline/surface-capture-control-r3-20261001/synthetic-report-success.json').read_text())
        cls.glb = (ROOT/'artifacts/depth-swap-sheets/sheets.glb').read_bytes()
    def test_full_synthetic_content(self):
        result = API['verify_sheets'](self.report, self.glb)
        self.assertEqual(result['samples'], 6441)
        self.assertFalse(result['processAdmitted'])
        self.assertFalse(result['visibilityVerified'])
    def reject_change(self, obj, key, value):
        saved = obj[key]
        try:
            obj[key] = value
            with self.assertRaises(ValueError): API['verify_sheets'](self.report, self.glb)
        finally: obj[key] = saved
    def test_missing_pose(self):
        self.reject_change(self.report, 'samples', self.report['samples'][:-1])
    def test_changed_pose(self):
        self.reject_change(self.report['samples'][100], 'pose', [0, 0])
    def test_reported_pass_cannot_hide_geometry(self):
        e = self.report['samples'][0]['meshes'][0]
        for key in ('actual', 'lastObservation'):
            e[key]['calculated']['positions'][0][0] += 2
        try:
            with self.assertRaisesRegex(ValueError, 'projection'): API['verify_sheets'](self.report, self.glb)
        finally:
            for key in ('actual', 'lastObservation'): e[key]['calculated']['positions'][0][0] -= 2
    def test_stale_consumption(self):
        e = self.report['samples'][0]['meshes'][0]
        self.reject_change(e['requested']['consumed'], 'updateVersion', e['actual']['consumed']['updateVersion'])
    def test_changed_restore(self):
        self.reject_change(self.report, 'liveAfter', [0, 0])
    def test_wrong_source(self):
        with self.assertRaises(ValueError): API['verify_sheets'](self.report, b'wrong source')
    def test_mesh_identity(self):
        self.reject_change(self.report['samples'][0]['meshes'][0]['requested'], 'sourceGuid', 'different-source')
    def test_history_mutation(self):
        self.reject_change(self.report['samples'][0]['historyAfter'], 'revision', -1)
    def test_random_pose_inventory(self):
        self.reject_change(self.report['samples'][-1], 'pose', [0, 0])
    def test_outer_protocol(self):
        r = dict(schemaVersion='mi3d-native-n2-v1',scope='N2_BOOTSTRAP_CREATE_GRID_READ',
            mi3dMode='sheet-geometry',runId='synthetic-run',status='PASS',failure=None,
            stageAStatus='NOT_EVALUATED',requestedVersion='5302',hostIdentity=None,
            checks=[dict(id=i,status='PASS',actual=True,evidenceKind='EXACT_HOST') for i in ('newCommand','newIdentity')],
            initialCounts=dict(meshes=0,deformers=0,parameters=0,parts=0),finallyRestored=True)
        r['finallyCounts'] = dict(r['initialCounts'])
        for phase,key in (('SEED','seed'),('NEW','document')):
            r[key] = dict(kind='MODEL',documentId=key+'-doc',modelId=key+'-model',editorModelId=key+'-editor')
            r['documentWait'+phase] = dict(phase=phase,reason='READY',attempts=1,documentPresent=True,
                modelPresent=True,kind='MODEL',documentId=r[key]['documentId'],snapshotModelId=r[key]['modelId'],editorModelId=r[key]['editorModelId'])
        r['sheetGeometry'] = dict(status='PASS',inputSha256=API['SHEETS_SHA'],importOutcome='COMMITTED',
            finallyRestored=True,initialState={'synthetic': True},restoredState={'synthetic': True},capture=self.report)
        self.assertEqual(API['verify_sheet_report'](r,'synthetic-run',self.glb)['samples'],6441)
        for target,key,value in ((r,'runId','other'),(r,'finallyRestored',False),
                (r['sheetGeometry'],'restoredState',{}),(r['document'],'documentId',r['seed']['documentId'])):
            old=target[key]
            try:
                target[key]=value
                with self.assertRaises(ValueError): API['verify_sheet_report'](r,'synthetic-run',self.glb)
            finally: target[key]=old

if __name__ == '__main__': unittest.main()
