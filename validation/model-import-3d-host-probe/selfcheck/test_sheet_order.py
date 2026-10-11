"""Protocol controls use manufactured calculated orders; no native order evidence."""
from pathlib import Path
import json,runpy,unittest
ROOT=Path(__file__).resolve().parents[3]
API=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-sheet-order.py'))
class SheetOrderTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.r=json.loads((ROOT/'build/model-import-3d-offline/surface-capture-control-r3-20261001/synthetic-report-success.json').read_text())
        cls.glb=(ROOT/'artifacts/depth-swap-sheets/sheets.glb').read_bytes()
        for row in cls.r['samples']:
            for i,m in enumerate(row['meshes']):
                for key in ('actual','lastObservation'):
                    m[key]['calculated']['drawOrder']=API['order'](i,row['pose'][0])
    def test_positive_protocol(self):
        r=API['verify_order'](self.r,self.glb)
        self.assertEqual(r['samples'],6441)
        self.assertGreater(r['minimumTransitionGapPixels'],80)
        self.assertFalse(r['pixelsVerified'])
    def test_stored_order_corruption(self):
        # Mutate both restoration snapshots so preservation alone cannot detect it.
        forms=[self.r[p][0]['forms'][0] for p in ('before','after')]
        for f in forms:f['drawOrder']=499
        try:
            with self.assertRaisesRegex(ValueError,'stored order'):API['verify_order'](self.r,self.glb)
        finally:
            for f in forms:f['drawOrder']=501
    def test_native_flat_order_rejected(self):
        m=self.r['samples'][0]['meshes'][0]
        for key in ('actual','lastObservation'):m[key]['calculated']['drawOrder']=500
        try:
            with self.assertRaisesRegex(ValueError,'front/back'):API['verify_order'](self.r,self.glb)
        finally:
            for key in ('actual','lastObservation'):m[key]['calculated']['drawOrder']=501
    def test_transition_rounding_not_assumed(self):
        row=next(r for r in self.r['samples'] if 0<abs(r['pose'][0])<1)
        old=[m['actual']['calculated']['drawOrder'] for m in row['meshes']]
        for m in row['meshes']:
            for key in ('actual','lastObservation'):m[key]['calculated']['drawOrder']=500
        try:self.assertEqual(API['verify_order'](self.r,self.glb)['samples'],6441)
        finally:
            for m,value in zip(row['meshes'],old):
                for key in ('actual','lastObservation'):m[key]['calculated']['drawOrder']=value
if __name__=='__main__':unittest.main()
