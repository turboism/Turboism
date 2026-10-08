#!/usr/bin/env python3
"""Synthetic raster-gate adversaries. No MOC/native/official renderer execution."""
import copy
from pathlib import Path
import runpy
import struct
import unittest
import zlib

HERE=Path(__file__).resolve().parent
FIXTURE=runpy.run_path(str(HERE/'test-geometry.py'))
R=runpy.run_path(str(HERE/'verify-raster.py'))


def png(color=(231,231,231,255)):
    def chunk(kind,body):
        return struct.pack('>I',len(body))+kind+body+struct.pack('>I',zlib.crc32(kind+body)&0xffffffff)
    rows=[]
    for y in range(512):
        rows.append(b'\0'+b''.join(bytes(color) if 128<=x<384 and 128<=y<384 else bytes(4) for x in range(512)))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('>IIBBBBB',512,512,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b''.join(rows)))+chunk(b'IEND',b'')


class RasterTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.capture,cls.ids=FIXTURE['fixture']();cls.texture=png()

    def test_1024_linear_texel_centres_and_bottom_origin(self):
        rows=[bytearray(4096) for _ in range(1024)]
        rows[700][4*800:4*801]=bytes((10,20,30,255))
        rows[700][4*801:4*802]=bytes((30,40,50,255))
        def tris(u,v):
            return [(0,(0,0,u,v),(0,2,u,v),(2,0,u,v),-4)]
        self.assertEqual(R['sample'](tris(800.5/1024,1-700.5/1024),rows,.5,.5,linear=True),(0,(10,20,30,255)))
        self.assertEqual(R['sample'](tris(801/1024,1-700.5/1024),rows,.5,.5,linear=True),(0,(20,30,40,255)))
        self.assertEqual(R['sample'](tris(800.5/1024,700.5/1024),rows,.5,.5,linear=True),(0,(0,0,0,0)))

    def test_source_texture_full_poses(self):
        import io
        from PIL import Image
        source_path=HERE.parents[1]/'artifacts/cube-textured/cube-textured.glb'
        oracle=runpy.run_path(str(HERE.parent/'model-import-3d-user-entry/verify-texture-pixels.py'))
        source=oracle['source_image'](FIXTURE['CUBE'],source_path)
        atlas=Image.new('RGBA',(1024,1024))
        for y in range(256):
            for x in range(512):atlas.putpixel((17+x,31+y),source.getpixel((max(0,min(255,x-1)),max(0,min(127,y-1)))))
        output=io.BytesIO();atlas.save(output,format='PNG')
        capture=copy.deepcopy(self.capture)
        _,patches=FIXTURE['G']['cube_source'](FIXTURE['CUBE'])
        for frame in capture['frames']:
            for row,patch in zip(frame['drawables'],patches):
                row['uvs']=[value for u,v in patch['uvs'] for value in ((18+256*u)/1024,1-(32+128*v)/1024)]
        result=R['verify'](capture,FIXTURE['CUBE'],self.ids,output.getvalue(),source_texture=source_path)
        self.assertEqual(len(result['frames']),9)
        self.assertEqual(sum(f['mismatches'] for f in result['frames']),0)
        for frame in capture['frames']:
            for row in frame['drawables']:row['uvs']=[.5]*len(row['uvs'])
        with self.assertRaises(ValueError):R['verify'](capture,FIXTURE['CUBE'],self.ids,output.getvalue(),source_texture=source_path)

    def test_source_depth_and_texture(self):
        result=R['verify'](self.capture,FIXTURE['CUBE'],self.ids,self.texture)
        self.assertEqual(len(result['frames']),9)
        self.assertGreater(sum(f['samples'] for f in result['frames']),500000)
        self.assertEqual(result['status'],'SOFTWARE_RASTER_CHECKED_NOT_FULL_ACCEPTANCE')

    def test_raster_failures(self):
        def no_culling(c):c['frames'][0]['drawables'][0]['constantFlags']=4
        def hidden(c):c['frames'][0]['drawables'][0]['dynamicFlags']=0
        def uv_background(c):
            for f in c['frames']:
                for r in f['drawables']:r['uvs']=[0]*len(r['uvs'])
        def flipped(c):
            for f in c['frames']:
                for r in f['drawables']:r['positions'][1::2]=[-v for v in r['positions'][1::2]]
        def duplicate(c):c['frames'][-1]=copy.deepcopy(c['frames'][0])
        def winding(c):
            for f in c['frames']:
                for r in f['drawables']:r['indices']=[0,1,2,0,2,3]
        def identity(c):
            for f in c['frames']:f['drawables'][0]['id']='unbound-mesh'
        for mutate in (no_culling,hidden,uv_background,flipped,duplicate,winding,identity):
            with self.subTest(mutation=mutate.__name__):
                altered=copy.deepcopy(self.capture);mutate(altered)
                with self.assertRaises(ValueError):R['verify'](altered,FIXTURE['CUBE'],self.ids,self.texture)
        with self.assertRaises(ValueError):R['verify'](self.capture,FIXTURE['CUBE'],self.ids,png((230,0,0,255)))

    def test_float32_roundoff_bound_does_not_admit_authored_modulation(self):
        for expected in (0,1):
            self.assertTrue(R['constant'](expected+2**-23,expected))
            self.assertFalse(R['constant'](expected+1e-5,expected))
            self.assertFalse(R['constant'](float('nan'),expected))
            self.assertFalse(R['constant'](float('inf'),expected))


if __name__=='__main__':unittest.main()
