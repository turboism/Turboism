"""Offline negative controls for source pixels and atlas-address UV checks."""
import io,runpy,struct
from pathlib import Path
import numpy as np
from PIL import Image

verify=runpy.run_path(str(Path(__file__).with_name('analyze-derived-export.py')))['verify_atlas_chart']
source=np.array([[[10,20,30,255],[0,0,0,0]],[[40,50,60,128],[70,80,90,255]]],dtype=np.uint8)
stream=io.BytesIO();Image.fromarray(source).save(stream,format='PNG')
chart=dict(chart=0,n=2,imageSize=2,positions=[(0,0),(1,1)],png=stream.getvalue())
placement=['image',1,3,4,2,2,False]
pages=[np.zeros((8,8,4),dtype=np.uint8) for _ in range(2)]
pages[1][4:6,3:5]=source
raw=bytes(16)+struct.pack('>ffff',3/4096,4/4096,4/4096,5/4096)
assert verify(raw,chart,placement,pages)['visiblePixelDifference']==0
def rejected(raw=raw,placement=placement,pages=pages):
 try:verify(raw,chart,placement,pages)
 except AssertionError:return
 raise AssertionError('corruption accepted')
rejected(placement=['image',0,3,4,2,2,False])
rejected(placement=['image',1,4,4,2,2,False])
rejected(raw=bytes(16)+struct.pack('>ffff',3/4096,4/4096,5/4096,5/4096))
for channel in (0,3):
 changed=[p.copy() for p in pages];changed[1][4,3,channel]-=1
 rejected(pages=changed)
changed=[p.copy() for p in pages];changed[1][4,4,:3]=255
assert verify(raw,chart,placement,changed)['transparentRgbDifferencePixels']==1
print('PASS source pixels/alpha, wrong page, shifted placement, corrupt UV negative controls; no host run')
