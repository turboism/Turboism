#!/usr/bin/env python3
import copy
from pathlib import Path
import runpy
import unittest
from PIL import Image

M=runpy.run_path(str(Path(__file__).with_name('verify-texture-pixels.py')))
ROOT=Path(__file__).resolve().parents[2]
CUBE=ROOT/'build/model-import-3d-inputs/cube-985496727e50/cube.glb'
TEXTURE=ROOT/'artifacts/cube-textured/cube-textured.glb'


class TexturePixelsTest(unittest.TestCase):
    def test_texel_centres_linear_clamp_and_top_origin(self):
        image=Image.new('RGBA',(2,2));image.putdata([(0,0,0,255),(100,0,0,255),(0,200,0,255),(100,200,0,255)])
        self.assertEqual(M['linear'](image,.25,.25),(0,0,0,255))
        self.assertEqual(M['linear'](image,.75,.75),(100,200,0,255))
        self.assertEqual(M['linear'](image,.5,.5),(50,100,0,255))
        self.assertEqual(M['linear'](image,0,1),(0,200,0,255))
        with self.assertRaises(ValueError):M['linear'](image,-.1,.5)

    def test_analytic_triangle_uv(self):
        # Neutral camera: source triangle projects to (512,512),(712,512),(512,312).
        vertices=[(0,0,0),(1,0,0),(0,1,0)]
        patch={'indices':[0,1,2,0,1,2],'uvs':[(0,0),(1,0),(0,1)]}
        u,v=M['face_uv'](vertices,patch,(0,0),562,462)
        self.assertAlmostEqual(u,.25);self.assertAlmostEqual(v,.25)

    def test_nine_source_poses_have_mark_coverage(self):
        for pose in M['POSES']:
            points,coverage,marked=M['reference'](CUBE,TEXTURE,pose)
            self.assertGreater(len(points),60000);self.assertGreater(sum(coverage),1000);self.assertGreater(marked,0)

    def test_persistence_intermediate_pose_inventory(self):
        for pose in ((-1,0),(1,0),(0,-1),(0,1)):
            with self.assertRaises(ValueError):M['reference'](CUBE,TEXTURE,pose)
            points,coverage,marked=M['reference'](CUBE,TEXTURE,pose,persistence=True)
            self.assertGreater(len(points),60000);self.assertGreater(sum(coverage),1000);self.assertGreater(marked,0)
        for pose in ((2,0),(0,30),(30,0)):
            with self.assertRaises(ValueError):M['reference'](CUBE,TEXTURE,pose,persistence=True)

    def test_detects_gray_flipped_texture_and_cropped_uv(self):
        expected,_,_=M['reference'](CUBE,TEXTURE,(0,0))
        rows=[bytearray(4096) for _ in range(1024)]
        for x,y,color in expected:rows[y][4*x:4*x+4]=bytes(color)
        self.assertEqual(M['inspect'](rows,expected)['mismatches'],0) # transport selfcheck, not native rendering
        image=M['source_image'](CUBE,TEXTURE);sources,patches=M['ENTRY']['G']['cube_source'](CUBE)
        points,_=M['RENDER']['samples'](CUBE.read_bytes(),(0,0))
        for mode in ('gray','flip','crop'):
            mutated=copy.deepcopy(rows)
            for x,y,face in points:
                if face is None:continue
                u,v=M['face_uv'](sources[face],patches[face],(0,0),x+.5,y+.5)
                color=(231,231,231,255) if mode=='gray' else M['linear'](image,u,1-v) if mode=='flip' else M['linear'](image,.25+.5*u,.25+.5*v)
                mutated[y][4*x:4*x+4]=bytes(color)
            self.assertGreater(M['inspect'](mutated,expected)['mismatches'],100,mode)


if __name__=='__main__':unittest.main()
