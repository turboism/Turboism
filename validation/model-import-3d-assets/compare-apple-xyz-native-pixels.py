#!/usr/bin/env python3
"""Compare existing actual XYZ captures against original-face depth/UV rendering.

Diagnostic only: include every pixel, retain AA differences; no acceptance tolerance.
"""
import argparse, hashlib, json, runpy, shutil, subprocess
from pathlib import Path
import numpy as np
from PIL import Image
HERE=Path(__file__).resolve().parent
SOURCE_SHA='3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999'
SOURCE_BIN_SHA='25306bfe4c896e341547499cd1bf8bb3fa8fe024c30ea483e98aae77f1c8251e'
POSES=[(0,0,0),(-180,0,0),(180,0,0),(0,-180,0),(0,180,0),(0,0,-180),(0,0,180),(90,0,0),(0,90,0),(0,0,90),(37.5,-112.5,45),(-150,75,-135)]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def require(ok,message):
 if not ok:raise ValueError(message)
def run(native,reference,out):
 report=json.loads((native/'state/report.json').read_text())
 original=(report['schemaVersion']=='mi3d-apple-xyz360-reopen-v1' and report.get('inputSha256')==SOURCE_SHA)
 derived=(report['schemaVersion']=='mi3d-apple-derived-save-v1'
          and report.get('candidateSha256') in ('f93fc84f9a71351977ecd1d85e82d39793278b3683ffb3c8b1b32d5049ef3520','00a706b57d43b33e9042c0f1fb8b66692c0be44e7525a5b4ef4739be393656cc','cd891e99c35c3d0e6302c591a98fa020f2ae32dc6bba0a856a282f361afc54f2')
          and report.get('testPointDeltaIncluded') is False)
 require(report['status']=='PASS' and (original or derived) and report['framesCaptured'] is True,'completed original reopen or exact unedited derived save capture')
 require(len(report['frames'])==12 and [tuple(f['poseXYZ']) for f in report['frames']]==POSES,'exact XYZ poses')
 source=reference/'source.bin';require(sha(source)==SOURCE_BIN_SHA,'frozen original source geometry/UV/texture')
 out.mkdir(parents=True,exist_ok=False);shutil.copyfile(source,out/'source.bin')
 cpp=(HERE/'apple-source-raster.cpp').read_text()
 start=cpp.index('    std::vector<std::pair<double,double>> poses;');end=cpp.index('        std::vector<double> depth',start)
 poses=','.join('{'+','.join(map(str,p))+'}' for p in POSES)
 cpp=cpp[:start]+f'''    std::vector<std::array<double,3>> poses={{{poses}}};
    for(size_t pose=0;pose<poses.size();pose++) {{
        double pitch=poses[pose][0]*std::acos(-1.)/180,yaw=poses[pose][1]*std::acos(-1.)/180,roll=poses[pose][2]*std::acos(-1.)/180;
        double cy=std::cos(yaw),sy=std::sin(yaw),cp=std::cos(pitch),sp=std::sin(pitch),cr=std::cos(roll),sr=std::sin(roll);
'''+cpp[end:]
 old='s[i]={512+2.954462184876577*(cy*a.x+sy*zp),512-2.954462184876577*yp,-sy*a.x+cy*zp,a.u,a.v};'
 require(old in cpp,'independent source projection template');cpp=cpp.replace(old,'double xp=cy*a.x+sy*zp;s[i]={512+2.954462184876577*(cr*xp-sr*yp),512-2.954462184876577*(sr*xp+cr*yp),-sy*a.x+cy*zp,a.u,a.v};')
 cpp=cpp.replace('poses[pose].first','poses[pose][1]').replace('poses[pose].second','poses[pose][0]')
 (out/'reference.cpp').write_text(cpp)
 with (out/'reference.log').open('w') as log:
  subprocess.run(['g++','-O2','-std=c++17',str(out/'reference.cpp'),'-o',str(out/'reference')],check=True,stdout=log,stderr=subprocess.STDOUT)
  subprocess.run([str((out/'reference').resolve()),str(out/'source.bin'),str(out/'frame-')],check=True,stdout=log,stderr=subprocess.STDOUT)
 metrics=runpy.run_path(str(HERE/'compare-apple-native-pixels.py'))['metrics'];rows=[]
 for i,f in enumerate(report['frames']):
  require(f['artifact']==f'xyz-frames/{i}.png','capture path');path=native/'state'/f['artifact'];require(sha(path)==f['sha256'],'capture hash')
  with Image.open(path) as image:
   require(image.size==(1024,1024) and image.mode=='RGBA','capture dimensions');actual=np.array(image)
  expected=np.fromfile(out/f'frame-{i}.rgba',dtype='u1').reshape(1024,1024,4);Image.fromarray(expected).save(out/f'reference-{i}.png')
  values,error=metrics(actual,expected);rgba=np.zeros_like(actual);rgba[:,:,0]=error;rgba[:,:,3]=255;Image.fromarray(rgba).save(out/f'difference-{i}.png')
  rows.append(dict(poseXYZ=f['poseXYZ'],nativeSha256=sha(path),referenceSha256=sha(out/f'frame-{i}.rgba'),**values))
 result=dict(status='APPLE_XYZ_ALL_PIXEL_DIAGNOSTICS_NOT_ACCEPTANCE',runId=report['runId'],nativeSchema=report['schemaVersion'],nativeReportSha256=sha(native/'state/report.json'),sourceSha256=SOURCE_SHA,sourceBinarySha256=SOURCE_BIN_SHA,allPixelsIncluded=True,masking=False,visibilityAccepted=False,antialiasing='Source pixel centers, native coverage retained; differences not classified as seam or occlusion automatically',frames=rows)
 (out/'report.json').write_text(json.dumps(result,indent=2)+'\n')
 (out/'artifact-manifest.json').write_text(json.dumps({p.name:sha(p) for p in out.iterdir() if p.is_file()},indent=2)+'\n')
 print(json.dumps([dict(poseXYZ=r['poseXYZ'],missingCoverage=r['missingCoverage'],extraCoverage=r['extraCoverage'],meanError=r['meanPremultipliedChannelError']) for r in rows],indent=2))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--native',type=Path,required=True);p.add_argument('--reference',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();run(a.native,a.reference,a.out)
