#!/usr/bin/env python3
"""XYZ oracle conventions and malformed coordinate evidence; no host acceptance."""
import gzip
import json
from pathlib import Path
import runpy
import struct
import tempfile
import unittest
import numpy as np
V=runpy.run_path(str(Path(__file__).with_name('verify-apple-xyz.py')))

class XYZOracleTest(unittest.TestCase):
    def test_independent_poses_match_frozen_java_sample(self):
        fixture=Path(__file__).resolve().parents[2]/'build/model-import-3d-offline/apple-xyz-core-recorder-r1-20261002/xyz-poses.json'
        if not fixture.exists():self.skipTest('optional frozen Java witness absent')
        self.assertEqual(V['poses'](),json.loads(fixture.read_text()))

    def test_axis_order_and_rotation_signs(self):
        pivot=np.array([0,130.6125549674034,0]);scale=2.954462184876577
        cases=[([0,0,1],[90,0,0],[scale,0]),([0,0,1],[0,90,0],[0,scale]),
               ([1,0,0],[0,0,90],[0,-scale])]
        for point,pose,offset in cases:
            actual=V['projection'](np.array([pivot+point]),pose)[0]
            np.testing.assert_allclose(actual,512+np.array(offset),atol=1e-12)

    def test_joint_rotation_uses_pitch_then_yaw_then_roll(self):
        point=np.array([2,133.6125549674034,4])
        # Sequential 90-degree rotations: Rx (2,-4,3), Ry (3,-4,-2), Rz (4,3,-2).
        np.testing.assert_allclose(V['projection'](np.array([point]),[90,90,90])[0],
                                   [512+4*2.954462184876577,512-3*2.954462184876577],atol=1e-12)

    def test_coordinate_reader_rejects_wrong_protocol_truncation_and_trailing(self):
        capture=dict(baselineDrawables=[dict(id='mesh',vertexCount=1)],frames=[dict(drawables=[
            dict(id='mesh',vertexCount=1,invariantSha256='a'*64)])])
        header=b'APXYZ001'+struct.pack('>ii',140,100258)
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/'positions.gz'
            for raw in (b'APXY0001'+header[8:]+struct.pack('>ff',1,2),header+b'x',header+struct.pack('>ff',1,2)+b'x'):
                with gzip.open(path,'wb') as stream:stream.write(raw)
                with self.assertRaises(ValueError):list(V['frames'](capture,path))
            with gzip.open(path,'wb') as stream:stream.write(header+struct.pack('>ff',1,2))
            self.assertEqual(list(V['frames'](capture,path))[0]['drawables'][0]['positions'].tolist(),[[1,2]])

    def test_missing_mesh_and_changed_invariants_rejected(self):
        capture=dict(baselineDrawables=[dict(id='mesh',vertexCount=1)],frames=[dict(drawables=[
            dict(id='mesh',vertexCount=1,invariantSha256=h*64)]) for h in ('a','b')])
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/'positions.gz'
            with gzip.open(path,'wb') as stream:stream.write(b'APXYZ001'+struct.pack('>ii',140,100258)+struct.pack('>ffff',1,2,3,4))
            with self.assertRaises(ValueError):list(V['frames'](capture,path))
            capture['frames'][0]['drawables'][0]['id']='different'
            with self.assertRaises(ValueError):list(V['frames'](capture,path))

    def test_derived_coordinate_header_cannot_be_confused_with_original(self):
        capture=dict(baselineDrawables=[],frames=[])
        with tempfile.TemporaryDirectory() as temporary:
            path=Path(temporary)/'positions.gz'
            with gzip.open(path,'wb') as stream:stream.write(b'APXYZ001'+struct.pack('>ii',140,80514))
            self.assertEqual(list(V['frames'](capture,path,80514)),[])
            with self.assertRaises(ValueError):list(V['frames'](capture,path))
            with gzip.open(path,'wb') as stream:stream.write(b'APXYZ001'+struct.pack('>ii',140,100258))
            with self.assertRaises(ValueError):list(V['frames'](capture,path,80514))

if __name__=='__main__':unittest.main()
