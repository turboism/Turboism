"""Entire report/raster fixture is synthetic; validates gates, never native rendering."""
from pathlib import Path
import copy,hashlib,json,runpy,struct,unittest,zlib
ROOT=Path(__file__).resolve().parents[3]
API=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-sheet-pixels.py'))
ORDER=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-sheet-order.py'))
FP=runpy.run_path(str(ROOT/'validation/model-import-3d-host-probe/verify-cube-render.py'))['fingerprint']
def png(rows):
    def chunk(kind,payload):return struct.pack('!I',len(payload))+kind+payload+struct.pack('!I',zlib.crc32(kind+payload))
    return b'\x89PNG\r\n\x1a\n'+chunk(b'IHDR',struct.pack('!2I5B',1024,1024,8,6,0,0,0))+chunk(b'IDAT',zlib.compress(b''.join(b'\0'+r for r in rows)))+chunk(b'IEND',b'')
class SheetFramesTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.raw=(ROOT/'artifacts/depth-swap-sheets/sheets.glb').read_bytes()
        cls.g=json.loads((ROOT/'build/model-import-3d-offline/surface-capture-control-r3-20261001/synthetic-report-success.json').read_text())
        for row in cls.g['samples']:
            for i,m in enumerate(row['meshes']):
                for key in ('actual','lastObservation'):m[key]['calculated']['drawOrder']=ORDER['order'](i,row['pose'][0])
        entry=dict(model='synthetic',canvas=[1024,1024,512,512,200],meshes=[dict(id=m['artMeshId'],snapshotSha256=FP(m),culling=False,visible=True) for m in cls.g['after']],parameters=[[axis,'synthetic','NORMAL',0,-30,0,30,False] for axis in ORDER['GEOMETRY']['AXES']])
        history=copy.deepcopy(cls.g['historyAfter']);frames=[];cls.artifacts={}
        for i,pose in enumerate(API['POSES']):
            capture=next(r for r in cls.g['samples'] if r['pose']==list(pose))
            state=copy.deepcopy(entry)
            for p,v in zip(state['parameters'],pose):p[3]=v
            points,colors,_,_=API['samples'](cls.raw,pose);rows=[bytearray(4096) for _ in range(1024)]
            for x,y,near,_ in points:
                if near is not None:rows[y][4*x:4*x+4]=bytes(colors[near])
            encoded=png(rows);name=f'sheet-render/{i}.png';cls.artifacts[name]=encoded
            frames.append(dict(index=i,pose=list(pose),artifact=name,status='CAPTURED',stateBefore=copy.deepcopy(state),stateAfter=state,
                historyBefore=copy.deepcopy(history),historyAfter=copy.deepcopy(history),consumption=copy.deepcopy(capture['meshes']),
                evaluationAfter=[copy.deepcopy(m['actual']) for m in capture['meshes']],artifactBytes=len(encoded),artifactSha256=hashlib.sha256(encoded).hexdigest()))
        cls.s=dict(status='CAPTURED',pixelAcceptance='REQUIRES_INDEPENDENT_ORACLE',inputSha256=API['SHA'],poses=[list(p) for p in API['POSES']],frames=frames,
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
            wrong=self.artifacts['sheet-render/8.png'];self.artifacts[name]=wrong;f['artifactBytes']=len(wrong);f['artifactSha256']=hashlib.sha256(wrong).hexdigest()
            with self.assertRaisesRegex(ValueError,'visibility pixel'):self.verify()
        finally:self.artifacts[name]=old;f['artifactBytes']=size;f['artifactSha256']=sha
if __name__=='__main__':unittest.main()
