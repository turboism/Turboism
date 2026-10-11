#!/usr/bin/env python3
"""Synthetic adversarial checks of the Core geometry oracle; no native execution."""
import copy
import math
from pathlib import Path
import runpy
import unittest

HERE = Path(__file__).resolve().parent
ORACLE = runpy.run_path(str(HERE/'verify-geometry.py'))
G = ORACLE['GEOMETRY']
CUBE = HERE.parents[1]/'build/model-import-3d-inputs/cube-985496727e50/cube.glb'


def fixture():
    sources, patches = G['cube_source'](CUBE)
    ids = ['source-face-'+str(i) for i in range(6)]
    frames = []
    for yaw, pitch in G['poses']():
        cy, sy = math.cos(math.radians(yaw)), math.sin(math.radians(yaw))
        cp, sp = math.cos(math.radians(pitch)), math.sin(math.radians(pitch))
        rows = []
        for i, (source, patch) in enumerate(zip(sources, patches)):
            positions = []
            for x, y, z in source:
                yp, zp = y*cp-z*sp, y*sp+z*cp
                positions.extend([2*(x*cy+zp*sy), 2*yp])
            rows.append(dict(id=ids[i], vertexCount=len(source), positions=positions,
                indices=[i for o in range(0,len(patch['indices']),3)
                         for i in reversed(patch['indices'][o:o+3])],
                uvs=[.5]*2*len(source), textureIndex=0, masks=[],
                opacity=1, constantFlags=0, dynamicFlags=1, drawOrder=500, renderOrder=i,
                multiplyColor=[1,1,1,1],screenColor=[0,0,0,0]))
        frames.append(dict(pose=[yaw,pitch],values=[yaw,pitch],drawables=rows))
    return dict(schemaVersion='mi3d-official-core-capture-v2', status='CAPTURED_NOT_VERIFIED',
        poseProtocol='mi3d-cube-geometry-377-v1', canvas=dict(size=[1024,1024],origin=[512,512],pixelsPerUnit=100),
        axes=G['PARAMETERS'],parameters=dict(ids=G['PARAMETERS'],minimum=[-30,-30],maximum=[30,30],
            default=[0,0],keyCounts=[45,45],keys=[G['KEYS'],G['KEYS']],repeats=[False,False]),frames=frames),ids


class GeometryTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.capture,cls.ids=fixture()

    def test_full_inventory_and_scope(self):
        result=ORACLE['verify'](self.capture,CUBE,self.ids)
        self.assertEqual(result['samples'],377)
        self.assertLess(result['maxErrorCanvasPixels'],1e-10)
        self.assertIn('NOT_FULL_ACCEPTANCE',result['status'])
        self.assertIn('native process admission',result['notCovered'])

    def test_drawable_order_is_not_identity(self):
        altered=copy.deepcopy(self.capture)
        for f in altered['frames']:f['drawables'].reverse()
        ORACLE['verify'](altered,CUBE,self.ids)

    def test_core_parameter_values_are_float32(self):
        altered=copy.deepcopy(self.capture)
        for f in altered['frames']:f['values']=[G['f32'](v) for v in f['values']]
        ORACLE['verify'](altered,CUBE,self.ids)
        altered['frames'][0]['values'][0]+=1e-4
        with self.assertRaises(ValueError):ORACLE['verify'](altered,CUBE,self.ids)

    def test_adversarial_changes_rejected(self):
        def vertex(c):c['frames'][-1]['drawables'][0]['positions'][0]+=.0011
        def flip_y(c):c['frames'][0]['drawables'][0]['positions'][1]*=-1
        def missing(c):c['frames'].pop()
        def repeated(c):c['frames'][-1]=copy.deepcopy(c['frames'][0])
        def keys(c):c['parameters']['keys'][0]=[-30,0,30]
        def values(c):c['frames'][0]['values']=[0,0]
        def identity(c):c['frames'][0]['drawables'][0]['id']='another-model'
        def topology(c):c['frames'][0]['drawables'][0]['indices']=[0,0,0,0,0,0]
        def winding(c):c['frames'][0]['drawables'][0]['indices']=[0,1,2,0,2,3]
        def uv(c):c['frames'][-1]['drawables'][0]['uvs'][0]=.6
        def opacity(c):c['frames'][0]['drawables'][0]['opacity']=0
        def culling(c):c['frames'][0]['drawables'][0]['constantFlags']=4
        def scale(c):c['canvas']['pixelsPerUnit']=200
        def nonfinite(c):c['frames'][0]['drawables'][0]['positions'][0]=float('nan')
        def ordering(c):c['frames'][0]['drawables'][0]['renderOrder']=1
        for mutate in (vertex,flip_y,missing,repeated,keys,values,identity,topology,winding,uv,opacity,culling,scale,nonfinite,ordering):
            with self.subTest(mutation=mutate.__name__):
                altered=copy.deepcopy(self.capture);mutate(altered)
                with self.assertRaises(ValueError):ORACLE['verify'](altered,CUBE,self.ids)

    def test_unused_parameters_require_admitted_export_and_stay_at_default(self):
        c=copy.deepcopy(self.capture)
        p=c['parameters']
        p['ids']=list(p['ids']) # Fixture shares the immutable axis inventory list.
        for key,value in dict(ids='ParamEyeLOpen',minimum=0,maximum=1,default=1,
                              keyCounts=0,keys=[],repeats=False).items():p[key].insert(0,value)
        for f in c['frames']:f['values'].insert(0,1)
        admitted=[['ParamEyeLOpen','Eye','NORMAL',1,0,1,1,False]]+[
            [axis,axis,'NORMAL',0,-30,0,30,False] for axis in G['PARAMETERS']]
        ORACLE['verify'](c,CUBE,self.ids,admitted)
        with self.assertRaises(ValueError):ORACLE['verify'](c,CUBE,self.ids)
        for mutation in ('value','definition','keys','duplicate','missing'):
            altered=copy.deepcopy(c)
            if mutation=='value':altered['frames'][-1]['values'][0]=0
            elif mutation=='definition':altered['parameters']['default'][0]=0
            elif mutation=='keys':altered['parameters']['keys'][0]=[0]
            elif mutation=='duplicate':altered['parameters']['ids'][0]=G['PARAMETERS'][0]
            else:altered['parameters']['minimum'].pop()
            with self.subTest(mutation=mutation),self.assertRaises(ValueError):
                ORACLE['verify'](altered,CUBE,self.ids,admitted)


if __name__=='__main__':unittest.main()
