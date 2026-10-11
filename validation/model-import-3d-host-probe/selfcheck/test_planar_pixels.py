"""Synthetic inverse-plane raster controls, not native rendering evidence."""
import math
from pathlib import Path
import runpy
import unittest
from PIL import Image

ROOT=Path(__file__).resolve().parents[3]
API=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-planar-pixels.py'))

def raster(raw,pose,flip=False,solid=False):
    # Independent inverse affine plane mapping; no oracle triangles/reference inventory.
    image=API['source'](raw)[3]
    yaw,pitch=map(math.radians,pose)
    cy,sy,cp,sp=math.cos(yaw),math.sin(yaw),math.cos(pitch),math.sin(pitch)
    rows=[bytearray(4096) for _ in range(1024)]
    for iy in range(2,1024,4):
        y=(512-(iy+.5))/(200*cp)
        for ix in range(2,1024,4):
            x=((ix+.5-512)/200-sy*sp*y)/cy
            if -1<=x<=1 and -.5<=y<=.5:
                u=(x+1)/2;v=.5-y
                color=(128,128,128,255) if solid else API['linear'](image,u,1-v if flip else v)
                rows[iy][ix*4:ix*4+4]=bytes(color)
    return rows

class PlanarPixelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.sources=[(ROOT/f'artifacts/plane-textured/{n}.glb').read_bytes() for n in ('plane','plane-seam')]
    def test_all_profiles_and_poses(self):
        for raw in self.sources:
            for pose in API['POSES']:
                result=API['inspect'](raster(raw,pose),raw,pose)
                self.assertEqual(result['mismatches'],0,result)
                self.assertGreater(min(result['triangleCoverage']),100)
                self.assertGreater(result['markSamples'],0)
                self.assertFalse(result['processAdmitted'])
    def test_texture_flip_and_solid_substitution(self):
        for raw in self.sources:
            for flip,solid in ((True,False),(False,True)):
                result=API['inspect'](raster(raw,(0,0),flip,solid),raw,(0,0))
                self.assertGreater(result['mismatches'],100)
    def test_wrong_pose(self):
        raw=self.sources[0]
        self.assertGreater(API['inspect'](raster(raw,(30,30)),raw,(-30,0))['mismatches'],100)
    def test_alpha_and_dimensions(self):
        raw=self.sources[0];rows=raster(raw,(0,0));rows[2][11]=255
        self.assertEqual(API['inspect'](rows,raw,(0,0))['mismatches'],1)
        with self.assertRaisesRegex(ValueError,'dimensions'):API['inspect'](rows[:-1],raw,(0,0))
    def test_source_and_pose_admission(self):
        with self.assertRaisesRegex(ValueError,'source'):API['reference'](b'wrong',(0,0))
        with self.assertRaisesRegex(ValueError,'pose'):API['reference'](self.sources[0],(45,0))
    def test_linear_texel_centres_and_clamp(self):
        image=Image.new('RGBA',(2,2));image.putdata([(0,0,0,255),(100,0,0,255),(0,100,0,255),(100,100,0,255)])
        self.assertEqual(API['linear'](image,.25,.25),(0,0,0,255))
        self.assertEqual(API['linear'](image,.5,.5),(50,50,0,255))
        self.assertEqual(API['linear'](image,1,1),(100,100,0,255))
    def test_continuous_seam_has_same_reference_pixels(self):
        # Pixel equality cannot prove six distinct source slots; geometry oracle does that.
        for pose in API['POSES']:
            self.assertEqual(API['reference'](self.sources[0],pose),API['reference'](self.sources[1],pose))

if __name__=='__main__':unittest.main()
