#!/usr/bin/env python3
"""Synthetic atlas/source/UV/persistence/export corruptions; never native acceptance."""
import argparse,copy,hashlib,json,runpy,struct,tempfile
from pathlib import Path
import numpy as np
from PIL import Image
V=runpy.run_path(str(Path(__file__).with_name('verify-surface-atlas.py')))
p=argparse.ArgumentParser();p.add_argument('--reopen-artifacts',type=Path,required=True);a=p.parse_args()
r=json.loads((a.reopen_artifacts/'report.json').read_text());glb=a.reopen_artifacts/'authorized-surface.glb'
raw=r['reopened'];count=len(raw['meshes']);image_id=raw['imageGroups'][0]['images'][0][0]
with tempfile.TemporaryDirectory(prefix='mi3d-surface-atlas-') as tmp:
 root=Path(tmp);(root/'packed-source').mkdir();(root/'official-export').mkdir();(root/'persistence').mkdir();(root/'atlas-capture/source-texture-render').mkdir(parents=True)
 def packed_state(x,y,atlas):
  s=copy.deepcopy(raw);s['atlases']=[[atlas,'Synthetic atlas',1024,1024,1]]
  for mesh in s['meshes']:
   mesh['uvs']=[[V['f32']((V['f32'](uv[0])*512+x)/1024),V['f32']((V['f32'](uv[1])*256+y)/1024)] for uv in mesh['uvs']]
  for meta in s['meshMetadata']:meta['input']=dict(kind='TEXTURE_ATLAS_REGION',image='',atlas=atlas)
  return s
 first=packed_state(2,2,'first-atlas');packed=packed_state(510,766,'second-atlas')
 def layout(x,y):return dict(atlasId='Synthetic atlas',pageWidth=1024,pageHeight=1024,pageCount=1,placements=[[image_id,0,x,y,512,256,False]])
 history=copy.deepcopy(r['sourceTextureRender']['restoredHistory']);h0=copy.deepcopy(history);h0.update(position=2,entries=['create-atlas','first-pack']);h1=copy.deepcopy(h0);h1.update(position=3,entries=['create-atlas','first-pack','repack'])
 capture=copy.deepcopy(r['sourceTextureRender']);protected=json.dumps(dict(packed,parameters=[p[:4] for p in packed['parameters']]),separators=(',',':'));digest=V['sha'](protected.encode())
 capture.update(protectedStateJson=protected,protectedStateSha256=digest,entryHistory=h1,restoredHistory=h1)
 for i,frame in enumerate(capture['frames']):
  (root/'atlas-capture'/frame['artifact']).write_bytes((a.reopen_artifacts/frame['artifact']).read_bytes())
  raw_target=root/frame['artifact'];raw_target.parent.mkdir(exist_ok=True);raw_target.write_bytes((a.reopen_artifacts/frame['artifact']).read_bytes())
  frame.update(stateBeforeSha256=digest,stateAfterSha256=digest,historyBefore=h1,historyAfter=h1)
 packed_json=json.dumps(packed,separators=(',',':'));packed_sha=V['sha'](packed_json.encode());old_json=json.dumps(raw,separators=(',',':'))
 rows=[]
 for i,mesh in enumerate(packed['meshes']):
  binary=b''.join(struct.pack('>f',v) for k in ('positions','uvs') for xy in mesh[k] for v in xy)+b''.join(struct.pack('>i',v) for v in mesh['indices'])
  name=f'packed-source/{i}.bin';(root/name).write_bytes(binary);rows.append(dict(id=mesh['artMeshId'],artifact=name,bytes=len(binary),sha256=V['sha'](binary)))
 output=b'synthetic save output';name='persistence/mi3d-surface-model.cmo3';(root/name).write_bytes(output)
 save=dict(commandStatus='EXECUTED',maximumBytes=64*1024*1024,before=packed,after=packed,historyBefore=h1,historyAfter=h1,artifact=name,artifactBytes=len(output),artifactSha256=V['sha'](output))
 moc=b'MOC3'+b'\0'*60;(root/'official-export/model.moc3').write_bytes(moc)
 _,_,_,source=V['BASE']['PIXELS']['source'](glb.read_bytes());padded=np.pad(np.asarray(source),((1,127),(1,255),(0,0)),mode='edge')
 texture=Image.new('RGBA',(1024,1024));texture.paste(Image.fromarray(padded),(510,766));texture.save(root/'official-export/texture-0.png');png=(root/'official-export/texture-0.png').read_bytes()
 e=dict(schemaVersion='mi3d-surface-official-export-v1',status='CAPTURED_REQUIRES_OFFICIAL_CORE_VERIFICATION',sourceRestored=True,
        restoredState=raw,beforeAtlasStateJson=old_json,beforeAtlasSha256=V['sha'](old_json.encode()),packedStateJson=packed_json,
        beforeExportSha256=packed_sha,afterExportSha256=packed_sha,historyBeforeExport=h1,historyAfterExport=h1,
        settings=[512,512,100,False,False,False,False,False],atlases=packed['atlases'],packedSource=rows,
        repack=dict(undoExact=True,redoExact=True,before=first,after=packed,layoutBefore=layout(2,2),layoutAfter=layout(510,766),historyBefore=h0,historyAfter=h1),
        packedAtlasLayout=layout(510,766),entryHistory=history,restoredHistory=history,atlasCapture=dict(sourceTextureRender=capture),packedPersistenceSave=save,
        moc=dict(artifact='official-export/model.moc3',bytes=len(moc),sha256=V['sha'](moc)),textures=[dict(index=0,artifact='official-export/texture-0.png',width=1024,height=1024,bytes=len(png),sha256=V['sha'](png))])
 r['officialExport']=e;assert V['verify_section'](r,glb,root)['saved']['bytes']==len(output)
 checks=0
 def reject(changed):
  global checks
  try:V['verify_section'](changed,glb,root)
  except (ValueError,KeyError):checks+=1;return
  raise AssertionError('corruption accepted')
 for key,value in [('schemaVersion','mi3d-apple-official-export-v1'),('sourceRestored',False),('restoredState',{}),('beforeAtlasSha256','0'*64),
                   ('afterExportSha256','0'*64),('historyAfterExport',{}),('settings',[0]*8),('atlases',[]),('packedSource',[])]:
  c=copy.deepcopy(r);c['officialExport'][key]=value;reject(c)
 for key,value in [('undoExact',False),('redoExact',False),('after',{}),('layoutAfter',{})]:
  c=copy.deepcopy(r);c['officialExport']['repack'][key]=value;reject(c)
 for key,value in [('maximumBytes',256*1024*1024),('artifactSha256','0'*64),('before',{}),('historyAfter',{})]:
  c=copy.deepcopy(r);c['officialExport']['packedPersistenceSave'][key]=value;reject(c)
 for field in ('positions','indices','forms','uvs'):
  c=copy.deepcopy(r);s=json.loads(c['officialExport']['packedStateJson']);s['meshes'][0][field]=[]
  c['officialExport']['packedStateJson']=json.dumps(s);c['officialExport']['repack']['after']=s
  c['officialExport']['beforeExportSha256']=c['officialExport']['afterExportSha256']=V['sha'](c['officialExport']['packedStateJson'].encode());reject(c)
 c=copy.deepcopy(r);c['officialExport']['atlasCapture']['sourceTextureRender']['frames'][0]['artifactSha256']='0'*64;reject(c)
 c=copy.deepcopy(r);c['officialExport']['moc']['sha256']='0'*64;reject(c)
 texture.putpixel((600,800),(1,2,3,255));texture.save(root/'official-export/texture-0.png');changed=(root/'official-export/texture-0.png').read_bytes()
 c=copy.deepcopy(r);c['officialExport']['textures'][0].update(bytes=len(changed),sha256=V['sha'](changed));reject(c)
 # Change a pixel outside the source sampling grid, with a self-consistent PNG receipt.
 path=root/'atlas-capture/source-texture-render/0.png'
 with Image.open(path) as im:changed_image=im.convert('RGBA');changed_image.putpixel((0,0),(3,0,0,0));changed_image.save(path)
 binary=path.read_bytes();c=copy.deepcopy(r);c['officialExport']['atlasCapture']['sourceTextureRender']['frames'][0].update(artifactBytes=len(binary),artifactSha256=V['sha'](binary));reject(c)
 print(json.dumps(dict(status='SYNTHETIC_SURFACE_ATLAS_REFUSALS_PASS',refusals=checks,meshCount=count,nativeExecuted=False)))
