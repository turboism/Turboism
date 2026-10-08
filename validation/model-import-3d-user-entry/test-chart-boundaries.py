import copy
from pathlib import Path
import runpy
import unittest
import numpy as np

measure=runpy.run_path(str(Path(__file__).with_name('measure-chart-boundaries.py')))['measure']


class BoundariesTest(unittest.TestCase):
    def fixture(self):
        charts=[dict(xyz=np.array([[0,0,0],[1,0,0]]),count=2,geometrySha256='a'),
                dict(xyz=np.array([[0,0,0],[0,1,0]]),count=2,geometrySha256='b')]
        meshes=[dict(id='a',vertexCount=2,geometrySha256='a'),dict(id='b',vertexCount=2,geometrySha256='b')]
        report=dict(imported=dict(meshes=meshes),frames=[dict(poseXYZ=[0,0,0],evaluated=[
            dict(id='a',positions=[[512,512],[600,512]]),dict(id='b',positions=[[513.3,512],[512,600]])])])
        return charts,report

    def test_measures_separation_and_coincident_restoration(self):
        charts,report=self.fixture();result=measure(charts,report)
        self.assertAlmostEqual(1.3,result['maximumSharedSourceSeparationPixels'],places=10)
        self.assertEqual([0,1],result['poses'][0]['charts'])
        report['frames'][0]['evaluated'][1]['positions'][0]=[512,512]
        self.assertEqual(0,measure(charts,report)['maximumSharedSourceSeparationPixels'])

    def test_rejects_foreign_geometry_and_incomplete_or_nonfinite_captures(self):
        charts,report=self.fixture()
        for mutate in (
            lambda r:r['imported']['meshes'][0].update(geometrySha256='foreign'),
            lambda r:r['frames'][0]['evaluated'][0].update(id='foreign'),
            lambda r:r['frames'][0]['evaluated'][0]['positions'].pop(),
            lambda r:r['frames'][0]['evaluated'][0]['positions'][0].__setitem__(0,float('nan')),
            lambda r:r['frames'][0]['evaluated'].pop()):
            bad=copy.deepcopy(report);mutate(bad)
            with self.assertRaises(ValueError):measure(charts,bad)


if __name__=='__main__':unittest.main()
