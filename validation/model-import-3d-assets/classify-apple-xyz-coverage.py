#!/usr/bin/env python3
"""Classify missing coverage without excluding any pixels from existing metrics."""
import argparse,hashlib,json
from pathlib import Path
import numpy as np
from PIL import Image

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def run(diagnostic,native,out):
 report=json.loads((diagnostic/'report.json').read_text());manifest=json.loads((diagnostic/'artifact-manifest.json').read_text());rows=[]
 if report['status']!='APPLE_XYZ_ALL_PIXEL_DIAGNOSTICS_NOT_ACCEPTANCE' or len(report['frames'])!=12:raise ValueError('complete diagnostic required')
 for i,row in enumerate(report['frames']):
  ref=diagnostic/f'frame-{i}.rgba';path=native/f'{i}.png'
  if sha(ref)!=manifest[ref.name] or sha(ref)!=row['referenceSha256'] or sha(path)!=row['nativeSha256']:raise ValueError('frozen pixel identity')
  expected=np.fromfile(ref,dtype='u1').reshape(1024,1024,4);actual=np.array(Image.open(path));visible=expected[:,:,3]>0
  # This is a partition of the reported missing pixels, not an error mask.
  interior=visible.copy();pad=np.pad(visible,1)
  for y in range(3):
   for x in range(3):interior &=pad[y:y+1024,x:x+1024]
  missing=visible & (actual[:,:,3]==0);inside=missing & interior;edge=missing & ~interior
  if int(missing.sum())!=row['missingCoverage']:raise ValueError('all-pixel missing count changed')
  rows.append(dict(poseXYZ=row['poseXYZ'],allMissingCoverage=int(missing.sum()),missingInside3x3SourceCoverage=int(inside.sum()),missingAtSourceCoverageEdge=int(edge.sum())))
 result=dict(status='COVERAGE_LOCATION_DIAGNOSTIC_NOT_ACCEPTANCE',allPixelsIncluded=True,masking=False,definition='Interior: all nine source pixel centers in the surrounding 3x3 are covered. Counts partition every missing pixel; no pixel dropped.',sourceDiagnosticSha256=sha(diagnostic/'report.json'),visibilityAccepted=False,frames=rows)
 with out.open('x') as f:json.dump(result,f,indent=2);f.write('\n')
 print(json.dumps(rows))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--diagnostic',type=Path,required=True);p.add_argument('--native',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();run(a.diagnostic,a.native,a.out)
