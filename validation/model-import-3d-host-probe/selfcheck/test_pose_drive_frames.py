"""Actual Java control-flow reports + independent synthetic rasters; no native evidence."""
from pathlib import Path
import hashlib,json,runpy,unittest
ROOT=Path(__file__).resolve().parents[3]
OLD=runpy.run_path(str(Path(__file__).with_name('test_planar_frames.py')))
Base=OLD['PlanarFramesTest']
EVIDENCE=ROOT/'build/model-import-3d-offline/pixel-history-oracle-r1-20261001'
class DrivenPlanarFramesTest(Base):
    fixture='plane';capture='plane'
    @classmethod
    def setUpClass(cls):
        cls.raw=(ROOT/f'artifacts/plane-textured/{cls.fixture}.glb').read_bytes()
        cls.g=json.loads((EVIDENCE/f'{cls.capture}-success/geometry.json').read_text())
        cls.s=json.loads((EVIDENCE/f'{cls.capture}-success/report.json').read_text())
        cls.artifacts={}
        for frame in cls.s['frames']:
            encoded=OLD['png'](OLD['RASTER'](cls.raw,frame['pose']))
            cls.artifacts[frame['artifact']]=encoded
            frame['artifactBytes']=len(encoded);frame['artifactSha256']=hashlib.sha256(encoded).hexdigest()
    def test_no_protocol_downgrade(self):self.reject(self.s,'historyProtocol',None)
    def test_history_handoff_tampered(self):self.reject(self.s['entryHistory'],'revision',-1)
class DrivenSeamFramesTest(DrivenPlanarFramesTest):
    fixture='plane-seam';capture='seam'
# Avoid rediscovering the imported base fixture.
del Base
SHEETS=runpy.run_path(str(Path(__file__).with_name('test_sheet_frames.py')))
class DrivenSheetFramesTest(SHEETS['SheetFramesTest']):
    @classmethod
    def setUpClass(cls):
        cls.raw=(ROOT/'artifacts/depth-swap-sheets/sheets.glb').read_bytes()
        cls.g=json.loads((EVIDENCE/'sheets-success/geometry.json').read_text())
        cls.s=json.loads((EVIDENCE/'sheets-success/report.json').read_text())
        # The geometry-only fake returns order500. Supply synthetic order values
        # for this frame-gate fixture, as in the legacy test; never native evidence.
        for row in cls.g['samples']:
            for i,m in enumerate(row['meshes']):
                for key in ('actual','lastObservation'):
                    m[key]['calculated']['drawOrder']=SHEETS['ORDER']['order'](i,row['pose'][0])
        cls.artifacts={}
        for frame in cls.s['frames']:
            points,colors,_,_=SHEETS['API']['samples'](cls.raw,tuple(frame['pose']))
            rows=[bytearray(4096) for _ in range(1024)]
            for x,y,near,_ in points:
                if near is not None:rows[y][4*x:4*x+4]=bytes(colors[near])
            encoded=SHEETS['png'](rows);cls.artifacts[frame['artifact']]=encoded
            frame['artifactBytes']=len(encoded);frame['artifactSha256']=hashlib.sha256(encoded).hexdigest()
    def test_no_protocol_downgrade(self):self.reject(self.s,'historyProtocol',None)
    def test_history_handoff_tampered(self):self.reject(self.s['entryHistory'],'revision',-1)
if __name__=='__main__':unittest.main()
