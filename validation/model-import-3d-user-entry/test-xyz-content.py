"""Synthetic oracle checks only; these do not constitute native acceptance."""
import copy
import math
from pathlib import Path
import runpy
import unittest

HERE=Path(__file__).resolve().parent
O=runpy.run_path(str(HERE/'verify-xyz-content.py'))
CUBE=HERE.parents[1]/'build/model-import-3d-inputs/cube-985496727e50/cube.glb'


class XYZOracleTest(unittest.TestCase):
    def test_outer_placement_requires_identity_bank_and_actual_parent_chain(self):
        bank=('RotationAuthoringSnapshot[deformerId=DeformerId[value=placement], sourceGuid=p, baseAngle=0.0, axes=[], '
              'cells=[Cell[formGuid=f, coordinates=[]]], forms=[Form[formGuid=f, '
              'value=RotationDeformerForm[angle=0.0, originX=0.0, originY=0.0, scale=1.0, reflectedX=false, reflectedY=false]]]]')
        report=dict(imported=dict(warps=[dict(id='warp')],rotations=[bank,'RotationAuthoringSnapshot[deformerId=DeformerId[value=roll], sourceGuid=r]']),
            outerPlacement=dict(id='placement',rollId='roll',warpId='warp',parent='',rollParent='placement',warpParent='roll',identityForm=[0,0,0,1,False,False]))
        O['rotation_inventory'](report)
        for mutation in (
            lambda r:r['outerPlacement'].update(rollParent='foreign'),
            lambda r:r['outerPlacement'].update(identityForm=[0,23,-11,1,False,False]),
            lambda r:r['imported']['rotations'].append(bank),
            lambda r:r['imported']['rotations'].__setitem__(0,bank.replace('axes=[]','axes=[Axis[parameterId=Roll]]')),
            lambda r:r['imported']['rotations'].__setitem__(0,bank.replace('scale=1.0','scale=1.25')),
            lambda r:r.pop('outerPlacement')):
            bad=copy.deepcopy(report);mutation(bad)
            with self.assertRaises(ValueError):O['rotation_inventory'](bad)

    def test_axis_convention(self):
        # Explicit known rotations guard axis/sign/order rather than copying the matrix.
        for vertex,pose,expected in (
            ((0,1,0),(90,0,0),(0,0,1)),
            ((0,0,1),(0,90,0),(1,0,0)),
            ((1,0,0),(0,0,90),(0,1,0)),
            ((0,1,0),(90,90,90),(0,1,0))):
            for a,b in zip(O['transform'](vertex,pose),expected):
                self.assertAlmostEqual(a,b,places=12)

    def fixture(self):
        sources,_=O['warp_source'](CUBE)
        meshes=[dict(id=f'm{i}') for i in range(6)]
        frames=[dict(poseXYZ=list(pose),evaluated=[dict(id=f'm{i}',positions=
            [list(O['project'](v,pose)[:2]) for v in source])
            for i,source in enumerate(sources)]) for pose in O['POSES']]
        return frames,meshes

    def test_complete_source_projection_and_seams(self):
        frames,meshes=self.fixture()
        self.assertEqual(len(O['geometry'](frames,meshes,CUBE)),12)

    def test_reject_corrupted_captures(self):
        frames,meshes=self.fixture()
        def shifted(f): f[10]['evaluated'][0]['positions'][0][0]+=4
        def origin(f):
            for row in f[0]['evaluated']:
                for p in row['positions']: p[0]-=512; p[1]-=512
        def seam(f): f[2]['evaluated'][0]['positions'][0][0]+=.01
        def foreign(f): f[0]['evaluated'][0]['id']='foreign'
        def nonfinite(f): f[0]['evaluated'][0]['positions'][0][0]=math.nan
        def missing(f): f.pop()
        def pose(f): f[0]['poseXYZ']=[0,0,1]
        def slots(f): f[0]['evaluated'][0]['positions'].pop()
        for mutation in (shifted,origin,seam,foreign,nonfinite,missing,pose,slots):
            with self.subTest(mutation=mutation.__name__):
                bad=copy.deepcopy(frames);mutation(bad)
                with self.assertRaises(ValueError): O['geometry'](bad,meshes,CUBE)


if __name__=='__main__': unittest.main()
