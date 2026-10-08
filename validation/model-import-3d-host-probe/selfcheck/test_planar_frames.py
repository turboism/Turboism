"""Entire report/raster fixture is synthetic; validates gates, never native rendering."""
from pathlib import Path
import copy,hashlib,json,runpy,struct,unittest,zlib
ROOT=Path(__file__).resolve().parents[3]
API=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-planar-pixels.py'))
GEOMETRY=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-surface-geometry.py'))
RASTER=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/selfcheck/test_planar_pixels.py'))['raster']
FP=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-cube-render.py'))['fingerprint']
def png(rows):
    def chunk(kind,payload):return struct.pack('!I',len(payload))+kind+payload+struct.pack('!I',zlib.crc32(kind+payload))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',1024,1024,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b''.join(b'\0'+r for r in rows)))+chunk(b'IEND',b'')
class PlanarFramesTest(unittest.TestCase):
    fixture="plane"
    @classmethod
    def setUpClass(cls):
        cls.raw=(ROOT/f'artifacts/plane-textured/{cls.fixture}.glb').read_bytes()
        cls.g=json.loads((ROOT/f'build/model-import-3d-offline/planar-capture-oracle-r1-20261001/{cls.fixture}-success.json').read_text())
        entry=dict(model='synthetic',canvas=[1024,1024,512,512,200],meshes=[dict(id=m['artMeshId'],snapshotSha256=FP(m),culling=True,visible=True) for m in cls.g['after']],parameters=[[axis,'synthetic','NORMAL',0,-30,0,30,False] for axis in GEOMETRY['AXES']])
        history=copy.deepcopy(cls.g['historyAfter']);frames=[];cls.artifacts={}
        for i,pose in enumerate(API['POSES']):
            capture=next(r for r in cls.g['samples'] if r['pose']==list(pose))
            state=copy.deepcopy(entry)
            for p,v in zip(state['parameters'],pose):p[3]=v
            rows=RASTER(cls.raw,pose)
            encoded=png(rows);name=f'planar-render/{i}.png';cls.artifacts[name]=encoded
            frames.append(dict(index=i,pose=list(pose),artifact=name,status='CAPTURED',stateBefore=copy.deepcopy(state),stateAfter=state,
                historyBefore=copy.deepcopy(history),historyAfter=copy.deepcopy(history),consumption=copy.deepcopy(capture['meshes']),
                evaluationAfter=[copy.deepcopy(m['actual']) for m in capture['meshes']],artifactBytes=len(encoded),artifactSha256=hashlib.sha256(encoded).hexdigest()))
        cls.s=dict(status='CAPTURED',pixelAcceptance='REQUIRES_INDEPENDENT_ORACLE',inputSha256=hashlib.sha256(cls.raw).hexdigest(),poses=[list(p) for p in API['POSES']],frames=frames,
            entryState=entry,restoredState=copy.deepcopy(entry),entryHistory=history,restoredHistory=copy.deepcopy(history),finallyRestored=True)
    def verify(self):return API['verify_frames'](self.s,self.g,self.raw,self.artifacts.__getitem__)
    def reject(self,obj,key,value):
        old=obj[key]
        try:
            obj[key]=value
            with self.assertRaises(ValueError):self.verify()
        finally:obj[key]=old
    def test_positive_synthetic_protocol(self):
        self.assertEqual(len(self.verify()['frames']),9)
    def test_wrong_pose(self):self.reject(self.s['frames'][0],'pose',[0,0])
    def test_state_changed(self):self.reject(self.s['frames'][0]['stateAfter'],'model','other')
    def test_post_render_identity(self):self.reject(self.s['frames'][0]['evaluationAfter'][0],'sourceGuid','other')
    def test_hash_mismatch(self):self.reject(self.s['frames'][0],'artifactSha256','0'*64)
    def test_wrong_png_even_rehashed(self):
        f=self.s['frames'][0];name=f['artifact'];old=self.artifacts[name];size=f['artifactBytes'];sha=f['artifactSha256']
        try:
            # Opposite yaw AND pitch signs project the z=0 plane identically.
            # Neutral pose has different geometry and is a valid wrong-image control.
            wrong=self.artifacts['planar-render/4.png'];self.artifacts[name]=wrong;f['artifactBytes']=len(wrong);f['artifactSha256']=hashlib.sha256(wrong).hexdigest()
            with self.assertRaisesRegex(ValueError,'visibility pixel'):self.verify()
        finally:self.artifacts[name]=old;f['artifactBytes']=size;f['artifactSha256']=sha
    def test_culling_changed(self):self.reject(self.s['entryState']['meshes'][0],'culling',False)
    def test_history_changed(self):self.reject(self.s['frames'][0]['historyAfter'],'revision',-1)

class SeamFramesTest(PlanarFramesTest):
    fixture="plane-seam"

if __name__=='__main__':unittest.main()
