#!/usr/bin/env python3
import copy
import itertools
from pathlib import Path
import runpy
import unittest

M=runpy.run_path(str(Path(__file__).with_name('verify-report.py')));G=M['G']
CUBE=Path(__file__).resolve().parents[2]/'build/model-import-3d-inputs/cube-985496727e50/cube.glb'


TEXTURE=Path(__file__).resolve().parents[2]/'artifacts/cube-textured/cube-textured.glb'


def fixture(textured=False,padded=False):
    assert not padded or textured
    width,height=(512,256) if padded else (256,128)
    axes=['Param3DYaw123456abcdef','Param3DPitch123456abcdef']
    base=dict(meshes=[],meshMetadata=[],parameters=[['standard',-1,0,1,0]],rawImages=[],imageGroups=[],parts=[])
    imported=copy.deepcopy(base);imported['parameters'] += [[p,-30,0,30,0] for p in axes]
    imported['parts']=[['part','3D','']]
    sources,patches=G['cube_source'](CUBE)
    for i,(vertices,patch) in enumerate(zip(sources,patches)):
        identity=f'mesh{i}';image='source-image' if textured else f'image{i}'
        uvs=patch['uvs'] if textured else [[G['f32'](.25+.5*u),G['f32'](.25+.5*v)] for u,v in patch['uvs']]
        if padded:uvs=[[G['f32']((256*u+1)/512),G['f32']((128*v+1)/256)] for u,v in uvs]
        cells=[];forms=[]
        for n,pose in enumerate(itertools.product(G['KEYS'],repeat=2)):
            guid=f'form{i}-{n}';cells.append(dict(formGuid=guid,coordinates=[dict(guid=f'axis{j}',value=v) for j,v in enumerate(pose)]))
            forms.append(dict(guid=guid,positions=G['truth'](vertices,*pose),drawOrder=500))
        imported['meshes'].append(dict(artMeshId=identity,sourceGuid=f'source{i}',positions=[[G['f32'](u*width),G['f32'](v*height)] for u,v in uvs],
            uvs=uvs,indices=patch['indices'],axes=[dict(guid=f'axis{j}',id=p,minimum=-30,default=0,maximum=30,keys=G['KEYS']) for j,p in enumerate(axes)],
            cells=cells,forms=forms,morphKeys=[]))
        imported['meshMetadata'].append(dict(id=identity,culling=True,visible=True,parent='part',input=dict(kind='MODEL_IMAGE',image=image,atlas='')))
        if not textured or i==0:
            imported['rawImages'].append([f'raw{i}',image,width,height]);imported['imageGroups'].append(dict(name=image,images=[[image,image,width,height]]))
    return dict(textureLayout='EDGE_PADDED_POT' if padded else 'ORIGINAL',schemaVersion='mi3d-user-entry-v1',scope='REAL_MENU_IMPORT_UNDO_REDO_ONLY',status='PASS',runId='run',
        seedDocumentId='seed',targetDocumentId='target',menuInvoked=True,realChooserApproved=True,appearance='SOURCE_MATERIAL' if textured else 'FACE_COLORS',
        confirmation='6 个网格 12150 45 / 45',completionMessage='导入完成',inputSha256=M['TEXTURED_SHA'] if textured else G['INPUT_SHA256'],
        baseline=base,imported=imported,afterUndo=copy.deepcopy(base),afterRedo=copy.deepcopy(imported),
        undoRestored=True,redoRestored=True,historyBefore=1,historyAfter=2,historyAfterUndo=1,historyAfterRedo=2)


class ReportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):cls.report=fixture()
    def test_full_stored_source_projection(self):
        self.assertEqual(M['verify'](self.report,CUBE,'run')['forms'],12150)
    def test_textured_full_uv_and_shared_image(self):
        result=M['verify'](fixture(True),CUBE,'run',TEXTURE)
        self.assertEqual(result['appearance'],'SOURCE_MATERIAL')
    def test_explicit_padding_layout_and_rejection_controls(self):
        r=fixture(True,True)
        self.assertEqual(M['verify'](r,CUBE,'run',TEXTURE,padded=True)['forms'],12150)
        with self.assertRaises(ValueError):M['verify'](r,CUBE,'run',TEXTURE)
        with self.assertRaises(ValueError):M['verify'](fixture(True),CUBE,'run',TEXTURE,padded=True)
        for mode in ('uv','source_position','dimensions','marker'):
            changed=copy.deepcopy(r)
            if mode=='uv':changed['imported']['meshes'][0]['uvs'][0][0]+=.001
            elif mode=='source_position':changed['imported']['meshes'][0]['positions'][0][0]+=1
            elif mode=='dimensions':changed['imported']['rawImages'][0][2]=256
            else:changed['textureLayout']='ORIGINAL'
            changed['afterRedo']=copy.deepcopy(changed['imported'])
            with self.subTest(mode=mode),self.assertRaises(ValueError):M['verify'](changed,CUBE,'run',TEXTURE,padded=True)

    def test_padded_uv_json_float_roundtrip_remains_bit_exact(self):
        import struct
        r=fixture(True,True)
        # JSON prints Java float values as short decimal strings, not their
        # expanded Python double representation. Nine digits round-trip float32.
        for mesh in r['imported']['meshes']:
            mesh['uvs']=[[float(format(v,'.9g')) for v in uv] for uv in mesh['uvs']]
        r['afterRedo']=copy.deepcopy(r['imported'])
        M['verify'](r,CUBE,'run',TEXTURE,padded=True)
        v=r['imported']['meshes'][0]['uvs'][0][0]
        bits=struct.unpack('<I',struct.pack('<f',v))[0]
        r['imported']['meshes'][0]['uvs'][0][0]=struct.unpack('<f',struct.pack('<I',bits+1))[0]
        r['afterRedo']=copy.deepcopy(r['imported'])
        with self.assertRaises(ValueError):M['verify'](r,CUBE,'run',TEXTURE,padded=True)

    def test_textured_mutations(self):
        source=fixture(True)
        def cropped(r):
            m=r['imported']['meshes'][0]
            m['uvs']=[[.25+.5*u,.25+.5*v] for u,v in m['uvs']]
            m['positions']=[[u*256,v*128] for u,v in m['uvs']]
        def pixels(r):r['imported']['meshes'][0]['positions'][0][0]+=1
        def substituted(r):r['appearance']='FACE_COLORS'
        def dimensions(r):r['imported']['rawImages'][0][2]=128
        def duplicated(r):r['imported']['rawImages'].append(['another','copy',256,128])
        def missing_binding(r):r['imported']['meshMetadata'][0]['input']['image']='missing'
        for mutate in (cropped,pixels,substituted,dimensions,duplicated,missing_binding):
            r=copy.deepcopy(source);mutate(r);r['afterRedo']=copy.deepcopy(r['imported'])
            with self.subTest(mutation=mutate.__name__),self.assertRaises(ValueError):M['verify'](r,CUBE,'run',TEXTURE)
        with self.assertRaises(ValueError):M['verify'](source,CUBE,'run')
        with self.assertRaises(ValueError):M['verify'](source,CUBE,'run',CUBE)
    def test_mutations(self):
        def geometry(r):r['imported']['meshes'][0]['forms'][100]['positions'][0][0]+=1
        def uv(r):r['imported']['meshes'][0]['uvs'][0][0]=0
        def tuple_missing(r):r['imported']['meshes'][0]['cells'].pop()
        def wrong_input(r):r['inputSha256']='wrong'
        def history(r):r['historyAfterRedo']+=1
        def no_undo(r):r['afterUndo']['rawImages']=[['leaked']]
        def binding(r):r['imported']['meshMetadata'][0]['input']['image']='missing'
        def culling(r):r['imported']['meshMetadata'][0]['culling']=False
        def axis(r):r['imported']['parameters'][1][1]=-90
        def fake_chooser(r):r['realChooserApproved']=False
        for mutate in (geometry,uv,tuple_missing,wrong_input,history,no_undo,binding,culling,axis,fake_chooser):
            r=copy.deepcopy(self.report);mutate(r)
            # For imported mutations also change Redo so the semantic check, not
            # merely history equality, must detect the corruption.
            if mutate not in (history,no_undo):r['afterRedo']=copy.deepcopy(r['imported'])
            with self.subTest(mutation=mutate.__name__),self.assertRaises(ValueError):M['verify'](r,CUBE,'run')


if __name__=='__main__':unittest.main()
