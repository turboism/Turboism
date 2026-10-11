"""Export artifact gate using synthetic MOC header/atlas, not official Core proof."""
from pathlib import Path
import copy,hashlib,io,runpy,unittest
from PIL import Image
ROOT=Path(__file__).resolve().parents[3]
VERIFY=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-whole-import.py'))['verify_cube_export']
API=runpy.run_path(str(ROOT/'validation/model-import-3d-user-entry/verify-texture-pixels.py'))
CUBE=ROOT/'build/model-import-3d-inputs/cube-985496727e50/cube.glb'
TEXTURE=ROOT/'artifacts/cube-textured/cube-textured.glb'
class ExportTest(unittest.TestCase):
    def setUp(self):
        h=dict(availability='AVAILABLE',generation=1,revision=1,position=1,documentBindingId='doc',managerBindingId='manager',entryIds=['pack'])
        self.report=dict(mi3dMode='cube-export',cubeAtlas=dict(inputSha256=hashlib.sha256(TEXTURE.read_bytes()).hexdigest(),textureLayout='EDGE_PADDED_POT',redone={},initialHistory=h,redoHistory=h,secondRequested=dict(placements=[['image',0,17,31,512,256,False]])))
        self.report['cubeExport']=dict(schemaVersion='mi3d-official-export-v1',status='CAPTURED_NOT_CORE_VERIFIED',failure=None,coreVerification='NOT_RUN',inputSha256=self.report['cubeAtlas']['inputSha256'],textureLayout='EDGE_PADDED_POT',settings=[512,512,100,False,False,False,False,False],before={},after={},historyBefore=h,historyAfter=h)
        self.image=Image.new('RGBA',(1024,1024));self.image.paste(API['source_image'](CUBE,TEXTURE),(18,32));self.encode()
    def encode(self):
        buf=io.BytesIO();self.image.save(buf,format='PNG');raw=buf.getvalue();moc=b'MOC3'+bytes(60)
        def record(path,data):return dict(path=path,bytes=len(data),sha256=hashlib.sha256(data).hexdigest())
        self.artifacts={'official-export/model.moc3':moc,'official-export/texture-0.png':raw}
        s=self.report['cubeExport'];s['moc']=record('official-export/model.moc3',moc)
        argb=b''.join(bytes((a,r,g,b)) for r,g,b,a in self.image.getdata())
        s['textures']=[dict(record('official-export/texture-0.png',raw),index=0,width=1024,height=1024,argbSha256=hashlib.sha256(argb).hexdigest())]
    def verify(self):return VERIFY(self.report,self.artifacts,cube=CUBE,texture=TEXTURE)
    def test_source_texels(self):self.assertEqual(self.verify()['status'],'EXPORTED_NOT_CORE_VERIFIED')
    def test_atlas_size_as_export_origin_rejected(self):
        self.report['cubeExport']['settings'][:2]=[1024,1024]
        with self.assertRaisesRegex(ValueError,'settings'):self.verify()
    def test_wrong_source_rejected(self):
        self.report['cubeExport']['inputSha256']='0'*64
        with self.assertRaisesRegex(ValueError,'profile'):self.verify()
    def test_gray_substitution_rejected(self):
        self.image.paste((231,231,231,255),(18,32,274,160));self.encode()
        with self.assertRaisesRegex(ValueError,'source texture'):self.verify()
    def test_uv_offset_rejected(self):
        self.report['cubeAtlas']['secondRequested']['placements'][0][2]+=1
        with self.assertRaisesRegex(ValueError,'source texture'):self.verify()
if __name__=='__main__':unittest.main()
