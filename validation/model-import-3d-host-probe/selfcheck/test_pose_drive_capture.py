"""Actual capture execution through authoring-history synthetic SDK; never native evidence."""
from pathlib import Path
import json,runpy,unittest
ROOT=Path(__file__).resolve().parents[3]
API=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-surface-geometry.py'))
H=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-pose-drive.py'))
class PoseHistoryCaptureTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.r=json.loads((ROOT/'build/model-import-3d-offline/pose-drive-capture-r1-20261001/plane-success.json').read_text())
        cls.raw=(ROOT/'artifacts/plane-textured/plane.glb').read_bytes()
    def test_complete_capture(self):
        result=API['verify_planar'](self.r,self.raw)
        self.assertEqual(result['samples'],5841)
        self.assertLess(result['maxErrorPixels'],.1)
    def reject(self,obj,key,value):
        old=obj[key]
        try:
            obj[key]=value
            with self.assertRaises(ValueError):H['verify'](self.r,API['AXES'])
        finally:obj[key]=old
    def test_wrong_parameter_action(self):
        self.reject(self.r['samples'][0]['historyDriven']['entries'][-1]['action'],'targetId','unrelated')
    def test_wrong_before_value(self):
        self.reject(self.r['samples'][0]['historyDriven']['entries'][-1]['action'],'before','123')
    def test_observation_mutation(self):
        self.reject(self.r['samples'][0]['historyAfter'],'revision',999)
    def test_wrong_undo_cursor(self):
        self.reject(self.r['samples'][0]['historyRestored'],'position',2)
    def test_unrestored_live_parameter(self):
        self.reject(self.r['samples'][0],'liveRestored',[0,0])
    def test_foreign_redo_between_poses(self):
        self.reject(self.r['samples'][1]['historyBeforeDrive']['entries'][-1],'entryId','foreign')
    def test_prefix_mutated(self):
        self.reject(self.r['samples'][0]['historyDriven']['entries'][0],'sha256','0'*64)
    def test_failed_observation_restores(self):
        r=json.loads((ROOT/'build/model-import-3d-offline/pose-drive-capture-r1-20261001/plane-failure.json').read_text())
        self.assertEqual(r['status'],'FAIL');self.assertTrue(r['restored'])
        self.assertTrue(r['samples'][0]['undoVerified'])
        self.assertEqual(r['liveBefore'],r['samples'][0]['liveRestored'])
        with self.assertRaises(ValueError):API['verify_planar'](r,self.raw)
    def test_final_history_tampered(self):
        self.reject(self.r['historyAfter'],'entryIds',[])
if __name__=='__main__':unittest.main()
