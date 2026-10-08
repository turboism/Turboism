"""Reference and synthetic pixel controls; no native raster evidence."""
from pathlib import Path
import runpy,unittest
ROOT=Path(__file__).resolve().parents[3]
API=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-sheet-pixels.py'))
class SheetPixelsTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.raw=(ROOT/'artifacts/depth-swap-sheets/sheets.glb').read_bytes()
    def raster(self,pose,wrong=False):
        points,colors,coverage,overlap=API['samples'](self.raw,pose)
        rows=[bytearray(4096) for _ in range(1024)]
        for x,y,near,both in points:
            if near is not None:rows[y][4*x:4*x+4]=bytes(colors[1-near if wrong and both else near])
        return rows
    def test_all_poses_and_source_depth(self):
        for pose in API['POSES']:
            points,colors,coverage,overlap=API['samples'](self.raw,pose)
            self.assertEqual(colors,((255,0,0,255),(0,0,255,255)))
            if pose[0]:
                # Plane equation independently says left nearer for negative yaw.
                self.assertEqual({near for x,y,near,both in points if both},{0 if pose[0]<0 else 1})
            self.assertEqual(API['inspect_pixels'](self.raster(pose),self.raw,pose)['mismatches'],0)
    def test_wrong_overlap_color(self):
        for pose in ((-30,0),(30,0)):
            r=API['inspect_pixels'](self.raster(pose,True),self.raw,pose)
            self.assertGreater(r['overlapMismatches'],100)
            self.assertEqual(r['mismatches'],r['overlapSamples'])
    def test_background_alpha(self):
        rows=self.raster((0,0));rows[2][4*2+3]=255
        self.assertEqual(API['inspect_pixels'](rows,self.raw,(0,0))['mismatches'],1)
    def test_unapproved_pose(self):
        with self.assertRaises(ValueError):API['samples'](self.raw,(31,0))
if __name__=='__main__':unittest.main()
