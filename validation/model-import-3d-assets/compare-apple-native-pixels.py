#!/usr/bin/env python3
"""All-pixel native/source diagnostics. No tolerance gate, masking, or acceptance claim."""
import argparse,hashlib,json
from pathlib import Path
import numpy as np
from PIL import Image
SOURCE_SHA='3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999'
POSES=[(y,p) for y in (-30,0,30) for p in (-30,0,30)]+[(-13.125,9.375),(16.875,-24.375)]
def require(ok,why):
 if not ok:raise ValueError(why)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def metrics(native,source):
 require(native.shape==source.shape==(1024,1024,4),'raster shape')
 delta=np.abs(native.astype(np.int16)-source.astype(np.int16));visible=source[:,:,3]>0;actual=native[:,:,3]>0
 # Premultiplied values make transparent background RGB irrelevant without dropping any pixels.
 a=native.astype(np.int32);b=source.astype(np.int32)
 a[:,:,:3]=(a[:,:,:3]*a[:,:,3,None]+127)//255;b[:,:,:3]=(b[:,:,:3]*b[:,:,3,None]+127)//255
 premul=np.abs(a-b);maximum=premul.max(axis=2)
 return {'pixelCount':1048576,'sourceVisible':int(visible.sum()),'nativeVisible':int(actual.sum()),
  'missingCoverage':int((visible&~actual).sum()),'extraCoverage':int((actual&~visible).sum()),
  'differentRawRGBA':int((delta.max(axis=2)>0).sum()),'differentPremultipliedRGBA':int((maximum>0).sum()),
  'maxPremultipliedChannelError':int(maximum.max()),'meanPremultipliedChannelError':premul.mean(axis=(0,1)).tolist(),
  'maximumErrorHistogram':np.bincount(maximum.ravel(),minlength=256).tolist()},maximum

def compare(report,native_dir,reference_dir,out):
 mode=report.get('mi3dMode')
 ui_scopes={'mi3d-apple-user-entry-v1':'REAL_MENU_IMPORT_UNDO_REDO_ONLY','mi3d-apple-menu-save-v1':'REAL_MENU_IMPORT_SAVE_ONLY','mi3d-apple-menu-save-compact-v1':'REAL_MENU_IMPORT_SAVE_ONLY'}
 if report.get('schemaVersion') in ui_scopes:mode='apple-user-entry'
 require(mode in ('source-warp-textured','canonical-warp-textured','canonical-warp-layered-textured','apple-user-entry') and report.get('status')=='PASS' and report.get('failure') is None,'completed native textured capture required')
 if mode=='apple-user-entry':
  section=report['sourceTextureRender'];source_field=None;prefix='source-texture-render'
  require(report['scope']==ui_scopes[report['schemaVersion']] and report['menuInvoked'] is True and report['realChooserApproved'] is True and section['status']=='CAPTURED' and section['restored'] is True,'completed real apple menu capture')
  require(report['inputSha256']==SOURCE_SHA,'native source identity')
  frames=section['frames']
 elif mode in ('canonical-warp-textured','canonical-warp-layered-textured'):
  section=report['canonicalWarp'];scope='CANONICAL_LAYERED_GLB_SOURCE_WARP_GEOMETRY_AND_CAPTURE_ONLY' if mode=='canonical-warp-layered-textured' else 'CANONICAL_GLB_SOURCE_WARP_GEOMETRY_AND_CAPTURE_ONLY';source_field='sourceSha256';prefix='canonical-warp-pixels'
 else:
  section=report['sourceWarpCandidate'];scope='FULL_SOURCE_WARP_TEXTURED_CAPTURE_ONLY';source_field='textureSourceSha256';prefix='source-warp-pixels'
 if mode!='apple-user-entry':
  require(section.get('scope')==scope and section.get('visibilityAcceptance') is False,'capture-only scope required')
  require(section.get(source_field)==SOURCE_SHA,'native source identity');frames=section['pixelFrames']
 reference=json.loads((reference_dir/'manifest.json').read_text());inventory=json.loads((reference_dir/'artifact-manifest.json').read_text())
 require(reference['sourceSha256']==SOURCE_SHA and reference['status']=='ORIGINAL_SOURCE_DEPTH_UV_MATERIAL_REFERENCE_NOT_NATIVE','original depth reference identity')
 require(len(reference['frames'])==len(frames)==11,'full frame count')
 require({p.name for p in native_dir.iterdir()}=={f'{i}.png' for i in range(11)},'native file inventory')
 rows=[];images=[]
 for i,pose in enumerate(POSES):
  frame=frames[i];name=f'{i}.png';raw=(native_dir/name).read_bytes()
  require(frame['artifact']==f'{prefix}/{name}' and tuple(frame['pose'])==pose and frame['artifactBytes']==len(raw) and frame['artifactSha256']==sha(raw),'native capture hash/pose')
  ref=reference['frames'][i];require(tuple(ref['pose'])==pose,'reference pose order')
  ref_name=f'frame-{i}.rgba';ref_raw=(reference_dir/ref_name).read_bytes()
  require(len(ref_raw)==1048576*4 and sha(ref_raw)==inventory[ref_name],'reference RGBA hash')
  with Image.open(native_dir/name) as image:
   require(image.size==(1024,1024) and image.mode=='RGBA','native RGBA dimensions');pixels=np.array(image)
  source=np.frombuffer(ref_raw,dtype=np.uint8).reshape(1024,1024,4)
  values,error=metrics(pixels,source);rows.append({'pose':pose,'nativeSha256':sha(raw),'referenceSha256':sha(ref_raw),**values});images.append(error)
 out.mkdir(parents=True,exist_ok=False)
 for i,error in enumerate(images):
  rgba=np.zeros((1024,1024,4),dtype=np.uint8);rgba[:,:,0]=error;rgba[:,:,3]=255;Image.fromarray(rgba).save(out/f'difference-{i}.png')
 result={'status':'PIXEL_DIFFERENCE_DIAGNOSTICS_NOT_ACCEPTANCE','nativeRunId':report['runId'],'sourceSha256':SOURCE_SHA,
 'sourceReference':'original triangles, UV, materials and nearest depth; pixel centers without AA',
 'allPixelsIncluded':True,'masking':False,'thresholdRelaxation':False,'visibilityAccepted':False,'frames':rows}
 (out/'report.json').write_text(json.dumps(result,indent=2)+'\n');return result
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);p.add_argument('--native-dir',type=Path,required=True);p.add_argument('--reference-dir',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
 compare(json.loads(a.report.read_text()),a.native_dir,a.reference_dir,a.out)
