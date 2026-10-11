"""Actual Java textured import forms -> persistence state gate. No host or raster claims."""
import copy
import json
import os
from pathlib import Path
import runpy
import struct
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]
MODULE=runpy.run_path(str(ROOT/'scripts/test/test_model_import_3d_whole_import.py'))
GATE=MODULE['GATE']
CUBE=ROOT/'build/model-import-3d-inputs/cube-985496727e50/cube.glb'
TEXTURE=ROOT/'artifacts/cube-textured/cube-textured.glb'

class TexturedPersistenceCaptureTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        produced=json.loads(Path(os.environ['MI3D_TEXTURED_PERSISTENCE_PRODUCER']).read_text())
        if produced['inputSha256']!='dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859':
            raise AssertionError('actual textured producer required')
        state=MODULE['fixture'](produced)['wholeImport']['committedState']
        del state['model'];state['schema']='mi3d-cube-persistence-state-v1';state['meshes'].pop(0)
        state['rawImages'][0][2:]=[512,256];state['imageGroups'][0]['images'][0][2:]=[512,256]
        for mesh in state['meshes']:
            del mesh['input']['modelId'];del mesh['input']['generation'];mesh['input']['atlasId']=''
        # Resource metadata/history are synthetic. Geometry/cells/forms are Java import output.
        cls.base=dict(state=state,snapshots=produced['written'])

    def verify(self,capture,**kwargs):
        return GATE['verify_cube_persistence_capture'](capture,CUBE,texture=TEXTURE,padded=True,**kwargs)

    def test_actual_textured_forms(self):
        self.assertEqual(self.verify(self.base),dict(meshes=6,forms=12150,renderStatus='NOT_EVALUATED'))

    def test_explicit_profile_required(self):
        for kwargs in ({},{'padded':True},{'texture':TEXTURE},{'texture':TEXTURE,'padded':1}):
            with self.subTest(kwargs=kwargs),self.assertRaises(ValueError):
                GATE['verify_cube_persistence_capture'](self.base,CUBE,**kwargs)

    def test_rejects_image_and_binding_mutations(self):
        for mutate in (
            lambda c:c['state']['rawImages'][0].__setitem__(2,256),
            lambda c:c['state']['imageGroups'][0]['images'][0].__setitem__(3,128),
            lambda c:c['state']['meshes'][0]['input'].update(imageId='another-image'),
            lambda c:c['state']['meshes'][0].update(culling=False),
        ):
            c=copy.deepcopy(self.base);mutate(c)
            with self.assertRaises(ValueError):self.verify(c)

    def test_rejects_one_ulp_uv_even_with_rehashed_snapshot(self):
        c=copy.deepcopy(self.base);s=c['snapshots'][0];u=s['uvs'][0][0]
        bits=struct.unpack('<I',struct.pack('<f',u))[0]
        s['uvs'][0][0]=struct.unpack('<f',struct.pack('<I',bits+1))[0]
        c['state']['meshes'][0]['snapshotSha256']=GATE['RENDER']['fingerprint'](s)
        with self.assertRaisesRegex(ValueError,'source topology/UV'):self.verify(c)

    def test_rejects_projected_source_with_rehashed_snapshot(self):
        c=copy.deepcopy(self.base);sources,_=GATE['CUBE']['cube_source'](CUBE)
        for row,snapshot,vertices in zip(c['state']['meshes'],c['snapshots'],sources):
            snapshot['positions']=GATE['CUBE']['truth'](vertices,0,0)
            row['snapshotSha256']=GATE['RENDER']['fingerprint'](snapshot)
        with self.assertRaisesRegex(ValueError,'image-space texture source geometry'):self.verify(c)

    def test_rejects_changed_source_glb(self):
        with tempfile.TemporaryDirectory() as folder:
            path=Path(folder)/'changed.glb';raw=bytearray(TEXTURE.read_bytes());raw[-1]^=1;path.write_bytes(raw)
            with self.assertRaisesRegex(ValueError,'textured input identity'):
                GATE['verify_cube_persistence_capture'](self.base,CUBE,texture=path,padded=True)

if __name__=='__main__':unittest.main()
