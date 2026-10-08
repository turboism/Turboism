"""Actual Java capture output, synthetic SDK/raster only; independent history chain tests."""
from pathlib import Path
import json,runpy,unittest
ROOT=Path(__file__).resolve().parents[3]
API=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-pose-drive.py'))
AXES=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-surface-geometry.py'))['AXES']
EVIDENCE=ROOT/'build/model-import-3d-offline/pixel-history-oracle-r1-20261001'
class PixelHistoryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.s=json.loads((EVIDENCE/'plane-success/report.json').read_text())
        cls.g=json.loads((EVIDENCE/'plane-success/geometry.json').read_text())
    def verify(self):return API['verify_pixels'](self.s,self.g,AXES)
    def reject(self,obj,key,value):
        old=obj[key]
        try:
            obj[key]=value
            with self.assertRaises(ValueError):self.verify()
        finally:obj[key]=old
    def test_actual_capture_all_profiles(self):
        for name in ('plane','seam','sheets'):
            s=json.loads((EVIDENCE/f'{name}-success/report.json').read_text())
            g=json.loads((EVIDENCE/f'{name}-success/geometry.json').read_text())
            self.assertTrue(API['verify_pixels'](s,g,AXES)['geometryToPixelOwnershipVerified'])
    def test_incoming_history_swap(self):self.reject(self.s['entryHistory'],'revision',-1)
    def test_wrong_root(self):self.reject(self.s['driverRoot'],'position',0)
    def test_foreign_redo_at_handoff(self):self.reject(self.s['frames'][0]['historyBeforeDrive']['entries'][-1],'entryId','foreign')
    def test_wrong_write(self):self.reject(self.s['frames'][0]['historyDriven']['entries'][-1]['action'],'targetId','foreign')
    def test_render_mutation(self):self.reject(self.s['frames'][0]['historyAfter'],'revision',-1)
    def test_render_compact_mutation(self):self.reject(self.s['frames'][0]['historyObserved'],'revision',-1)
    def test_missing_undo(self):self.reject(self.s['frames'][0],'undoVerified',False)
    def test_wrong_live_restored(self):self.reject(self.s['frames'][0],'liveRestored',[1,0])
    def test_final_tail_tampered(self):self.reject(self.s['historyFinal'],'entryIds',[])
    def test_final_summary_tampered(self):self.reject(self.s['restoredHistory'],'revision',-1)
    def test_wrong_protocol(self):self.reject(self.s,'historyProtocol','unknown')
    def test_failed_capture_not_accepted(self):
        for name in ('plane','seam','sheets'):
            s=json.loads((EVIDENCE/f'{name}-failure/report.json').read_text())
            self.assertTrue(s['finallyRestored']);self.assertTrue(s['frames'][0]['undoVerified'])
            g=json.loads((EVIDENCE/f'{name}-failure/geometry.json').read_text())
            with self.assertRaises((ValueError,KeyError)):API['verify_pixels'](s,g,AXES)
if __name__=='__main__':unittest.main()
