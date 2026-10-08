#!/usr/bin/env python3
"""Independent surface source/UV repack, pixels, typed SAVE_AS and exporter content."""
import hashlib,io,json,runpy,struct
from pathlib import Path
import numpy as np
from PIL import Image
BASE=runpy.run_path(str(Path(__file__).with_name('verify-surface-menu.py')))
PAIR=runpy.run_path(str(Path(__file__).with_name('verify-surface-reopen.py')))
SAVE=runpy.run_path(str(Path(__file__).with_name('verify-surface-save.py')))
def require(ok,why):
 if not ok:raise ValueError('SURFACE_ATLAS: '+why)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def f32(v):return BASE['G']['f32'](v)
def bits(v):return struct.unpack('>I',struct.pack('>f',f32(v)))[0]

def verify_meshes(raw,packed,placement):
 require(len(raw)==len(packed) in (1,2),'complete surface mesh inventory')
 for old,new in zip(raw,packed):
  require(set(old)==set(new) and all(old[k]==new[k] for k in old if k!='uvs'),'all source XY/indices/forms/identities exact')
  require(len(new['uvs'])==len(old['uvs']),'all UV slots retained')
  for previous,current in zip(old['uvs'],new['uvs']):
   wanted=[f32((f32(previous[0])*512+placement[2])/1024),f32((f32(previous[1])*256+placement[3])/1024)]
   require(len(current)==2 and all(abs(bits(a)-bits(b))<=1 for a,b in zip(current,wanted)), 'fixed source UV atlas transform within one float32 ULP')

def verify_section(r,glb,artifacts):
 e=r['officialExport'];original=r['reopened'];packed=json.loads(e['packedStateJson']);repack=e['repack']
 require(e['schemaVersion']=='mi3d-surface-official-export-v1' and e['status']=='CAPTURED_REQUIRES_OFFICIAL_CORE_VERIFICATION'
         and e['sourceRestored'] is True and 'failure' not in e,'explicit complete native export capture')
 require(json.loads(e['beforeAtlasStateJson'])==e['restoredState']==original
         and sha(e['beforeAtlasStateJson'].encode())==e['beforeAtlasSha256'],'source before/after atlas exact')
 digest=sha(e['packedStateJson'].encode());require(e['beforeExportSha256']==e['afterExportSha256']==digest
         and e['historyBeforeExport']==e['historyAfterExport'],'export state/history protected')
 require(e['settings']==[512,512,100,False,False,False,False,False],'fixed export origin/units/options')
 count=len(original['meshes']);require(count in (1,2) and len(e['atlases'])==1 and e['atlases'][0][2:]==[1024,1024,1],'one complete bounded atlas')
 require(packed['atlases']==e['atlases'] and packed['deformers']==[],'complete atlas inventory')
 for state in (repack['before'],packed):
  for field in ('deformers','parameters','parts','rawImages','imageGroups','canvas'):require(state[field]==original[field],'protected '+field)
  require(len(state['meshMetadata'])==count,'complete metadata')
  atlas=state['atlases'][0][0];require(len(state['atlases'])==1 and state['atlases'][0][2:]==[1024,1024,1],'atlas state dimensions')
  for old,new in zip(original['meshMetadata'],state['meshMetadata']):
   require({k:v for k,v in old.items() if k!='input'}=={k:v for k,v in new.items() if k!='input'}
           and new['input']==dict(kind='TEXTURE_ATLAS_REGION',image='',atlas=atlas),'exact native region binding migration')
 require(repack['undoExact'] is True and repack['redoExact'] is True and repack['after']==packed and repack['before']!=packed,'actual native repack Undo/Redo')
 a,b=repack['layoutBefore'],repack['layoutAfter'];require(b==e['packedAtlasLayout'] and a!=b and a['atlasId']==b['atlasId']==e['atlases'][0][1],'logical atlas layout')
 for layout in (a,b):require(layout['pageWidth']==layout['pageHeight']==1024 and layout['pageCount']==1 and len(layout['placements'])==1,'complete fixed-size layout')
 p,q=a['placements'][0],b['placements'][0];image=original['imageGroups'][0]['images'][0][0]
 require(p==[image,0,2,2,512,256,False] and q==[image,0,510,766,512,256,False],'original-size reflected placement')
 verify_meshes(original['meshes'],repack['before']['meshes'],p);verify_meshes(original['meshes'],packed['meshes'],q)
 h0,h1=repack['historyBefore'],repack['historyAfter'];require(h1['position']==h0['position']+1 and h1['entries'][:h0['position']]==h0['entries'][:h0['position']]
         and all(h0[k]==h1[k] for k in ('availability','generation','document','manager')),'one native repack history entry')
 root,restored=e['entryHistory'],e['restoredHistory'];require(root==r['sourceTextureRender']['restoredHistory']
         and all(root[k]==restored[k] for k in ('availability','generation','document','manager','position'))
         and root['entries'][:root['position']]==restored['entries'][:root['position']],'full restored root binding/prefix')
 require(len(e['packedSource'])==count and {p.name for p in (artifacts/'packed-source').iterdir()}=={f'{i}.bin' for i in range(count)},'packed geometry namespace')
 for i,(mesh,row) in enumerate(zip(packed['meshes'],e['packedSource'])):
  name=f'packed-source/{i}.bin';raw=(artifacts/name).read_bytes()
  expected=b''.join(struct.pack('>f',v) for key in ('positions','uvs') for point in mesh[key] for v in point)+b''.join(struct.pack('>i',v) for v in mesh['indices'])
  require(row['id']==mesh['artMeshId'] and row['artifact']==name and raw==expected and len(raw)==row['bytes'] and sha(raw)==row['sha256'],'independent complete packed source bytes')
 capture=e['atlasCapture'];frames=capture['sourceTextureRender']['frames']
 require({p.name for p in (artifacts/'atlas-capture/source-texture-render').iterdir()}=={f'{i}.png' for i in range(9)},'exact atlas raster namespace')
 capture=dict(capture,renderVerification=r['renderVerification'])
 pixels=BASE['verify_render'](capture,glb,lambda name:(artifacts/'atlas-capture'/name).read_bytes(),packed,capture['sourceTextureRender']['entryHistory']['position'])
 differences=[]
 for x,y in zip(frames,r['sourceTextureRender']['frames']):
  require(x['pose']==y['pose'],'same raw/atlas raster pose')
  with Image.open(io.BytesIO((artifacts/'atlas-capture'/x['artifact']).read_bytes())) as im:atlas=np.asarray(im.convert('RGBA')).astype(np.int16)
  with Image.open(io.BytesIO((artifacts/y['artifact']).read_bytes())) as im:raw_pixels=np.asarray(im.convert('RGBA')).astype(np.int16)
  require(atlas.shape==raw_pixels.shape==(1024,1024,4),'complete unmasked raw/atlas rasters')
  delta=np.abs(atlas-raw_pixels);require(np.max(delta[:,:,:3])<=2 and np.max(delta[:,:,3])==0,'unmasked raw/atlas RGB2 and exact alpha')
  differences.append(dict(pose=x['pose'],differentPixels=int(np.count_nonzero(np.any(delta>0,axis=2))),maximumRGBDelta=int(np.max(delta[:,:,:3])),alphaExact=True))
 s=e['packedPersistenceSave'];require(s['historyBefore']==s['historyAfter']==capture['sourceTextureRender']['restoredHistory'],'packed save full history')
 saved=SAVE['verify_save'](dict(schemaVersion='mi3d-surface-menu-v1',scope='REAL_MENU_IMPORT_UNDO_REDO_SOURCE_RENDER',saveReopen='SAVED_REOPEN_NOT_RUN',imported=packed,sourceTextureRender=capture['sourceTextureRender'],persistenceSave=dict(s,historyUnchanged=True)),artifacts)
 moc=e['moc'];file=artifacts/'official-export/model.moc3';raw=file.read_bytes()
 require(moc['artifact']=='official-export/model.moc3' and 64<=len(raw)==moc['bytes']<=32*1024*1024 and raw[:4]==b'MOC3' and sha(raw)==moc['sha256'],'bounded actual official MOC')
 require(len(e['textures'])==1,'one official texture');t=e['textures'][0];file=artifacts/'official-export/texture-0.png';raw=file.read_bytes()
 require(t['index']==0 and t['artifact']=='official-export/texture-0.png' and t['width']==t['height']==1024 and len(raw)==t['bytes'] and sha(raw)==t['sha256'],'official texture identity')
 _,_,_,image=BASE['PIXELS']['source'](Path(glb).read_bytes());padded=np.pad(np.asarray(image),((1,127),(1,255),(0,0)),mode='edge')
 with Image.open(file) as im:
  require(im.size==(1024,1024),'actual atlas dimensions');actual=np.asarray(im.convert('RGBA'))
 require(np.array_equal(actual[766:1022,510:1022],padded),'all original padded source texture pixels exact in exported atlas')
 return dict(status='EXACT_SURFACE_REPACK_UNDO_REDO_PACKED_SAVE_AND_OFFICIAL_EXPORT_CONTENT',pixels=pixels,rawAtlasUnmaskedComparison=differences,saved=saved,moc=moc,textures=e['textures'],officialExportAccepted=False,packedPersistenceAccepted=False,goalComplete=False)

def verify(r,saved,glb,run,artifacts):
 seed=PAIR['verify'](r,saved,glb,run,lambda name:(artifacts/name).read_bytes())
 return dict(seed=seed,**verify_section(r,glb,artifacts))
