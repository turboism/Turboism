"""Synthetic UI checker integrity tests only, without native or pixel acceptance."""
import argparse,copy,io,json,runpy,hashlib
from pathlib import Path
import numpy as np
from PIL import Image
p=argparse.ArgumentParser();p.add_argument('--compact',action='store_true');p.add_argument('--prepared',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();a.out.mkdir(exist_ok=False,parents=True)
v=runpy.run_path(str(Path(__file__).with_name('verify-apple-report.py')));meshes,controls=v['C']['prepared'](a.prepared);keys=v['KEYS'];orders=np.frombuffer((a.prepared/'orders-i32.bin').read_bytes(),dtype='>i4').reshape(289,98);sha=v['sha']
layer_keys=keys[::2] if a.compact else keys;layer_forms=len(layer_keys)**2
yaw='Param3DYaw012345abcdef';pitch='Param3DPitch012345abcdef';axes=[dict(id=yaw,guid='gy',keys=layer_keys),dict(id=pitch,guid='gp',keys=layer_keys)]
base=dict(meshes=[],meshMetadata=[],rawImages=[],imageGroups=[],deformers=[],parameters=[],parts=[]);snapshots=[];metadata=[];locals=[]
(a.out/'apple-locals').mkdir();(a.out/'source-texture-render').mkdir()
for i,m in enumerate(meshes):
 raw=m['local'].astype('>f4').tobytes();name=f'apple-locals/{i}.xy';(a.out/name).write_bytes(raw);locals.append(dict(id=f'mesh-{i}',artifact=name,sha256=sha(raw)))
 forms=[dict(guid=f'f-{i}-{j}',coordinates=[['gy',pose[0]],['gp',pose[1]]],drawOrder=int(orders[j,i])) for j,pose in enumerate(controls) if pose[0] in layer_keys and pose[1] in layer_keys]
 snapshots.append(dict(id=f'mesh-{i}',sourceGuid=f'source-{i}',vertexCount=m['count'],triangleCount=m['triangles'],axisCount=2,formCount=layer_forms,cellCount=layer_forms,axes=copy.deepcopy(axes),forms=forms,authoringSha256='a'*64,geometrySha256=m['sourceMeshSha256'],localSha256=sha(raw)))
 metadata.append(dict(id=f'mesh-{i}',culling=True,visible=True,parent='part',input=dict(kind='MODEL_IMAGE',image='image',atlas='')))
warp=dict(id='warp',sourceGuid='ws',rows=119,columns=238,formCount=289,axes=[dict(axis,keys=keys) for axis in axes],authoringSha256='b'*64,forms=[dict(guid=f'w-{j}',coordinates=[['gy',pose[0]],['gp',pose[1]]],xySha256=digest) for j,(pose,digest) in enumerate(controls.items())])
imported=dict(meshes=snapshots,meshMetadata=metadata,rawImages=[['image','source',1024,256]],imageGroups=[dict(name='source',images=[['image','source',1024,256]])],deformers=[warp],parameters=[[yaw,-30,0,30,0],[pitch,-30,0,30,0]],parts=[['part','3D','']])
protected=copy.deepcopy(imported);protected['parameters']=[p[:4] for p in protected['parameters']];protected_json=json.dumps(protected);digest=sha(protected_json.encode());frames=[]
b=io.BytesIO();Image.new('RGBA',(1024,1024),(0,0,0,0)).save(b,format='PNG');png=b.getvalue()
for i,pose in enumerate(v['POSES']):
 requested=[];actual=[]
 for m in snapshots:
  values=[['gy',yaw,pose[0]],['gp',pitch,pose[1]]];r=dict(artMeshId=m['id'],sourceGuid=m['sourceGuid'],live=dict(version=2,values=values),consumed=dict(version=0,values=values));requested.append(r);q=copy.deepcopy(r);q['consumed']['version']=1;actual.append(q)
 name=f'source-texture-render/{i}.png';(a.out/name).write_bytes(png)
 frames.append(dict(index=i,pose=list(pose),artifact=name,artifactBytes=len(png),artifactSha256=sha(png),waitReason='CONSUMED',historyBefore=dict(position=1),historyAfter=dict(position=1),stateBeforeSha256=digest,stateAfterSha256=digest,requested=requested,lastObservation=actual,evaluationBefore=copy.deepcopy(actual),evaluationAfter=copy.deepcopy(actual)))
history=dict(position=1,document='document',manager='manager')
r=dict(schemaVersion='mi3d-apple-user-entry-v1',runId='synthetic-only',scope='REAL_MENU_IMPORT_UNDO_REDO_ONLY',status='PASS',inputSha256=v['SOURCE_SHA'],seedDocumentId='seed',targetDocumentId='document',menuInvoked=True,realChooserApproved=True,appearance='SOURCE_MATERIAL',confirmation='98 个曲面网格，17 / 17',completionMessage='导入完成',baseline=base,imported=imported,afterUndo=base,afterRedo=copy.deepcopy(imported),undoRestored=True,redoRestored=True,historyBefore=0,historyAfter=1,historyAfterUndo=0,historyAfterRedo=1,layerLocalCaptures=locals,renderVerification='CAPTURED_REQUIRES_INDEPENDENT_ORACLE',sourceTextureRender=dict(schemaVersion='mi3d-apple-texture-render-v1',status='CAPTURED',restored=True,axes=[yaw,pitch],protectedStateJson=protected_json,protectedStateSha256=digest,frames=frames,entryHistory=history,restoredHistory=history))
result=v['verify'](r,'synthetic-only',a.prepared,a.out,layer_keys);assert not result['goalComplete'];checks=[]
def reject(name,mutate):
 q=copy.deepcopy(r);mutate(q)
 if q['imported']!=r['imported']:
  q['afterRedo']=copy.deepcopy(q['imported']);protected=copy.deepcopy(q['imported']);protected['parameters']=[p[:4] for p in protected['parameters']]
  protected_json=json.dumps(protected);digest=sha(protected_json.encode());q['sourceTextureRender']['protectedStateJson']=protected_json;q['sourceTextureRender']['protectedStateSha256']=digest
  for f in q['sourceTextureRender']['frames']:f['stateBeforeSha256']=f['stateAfterSha256']=digest
 try:v['verify'](q,'synthetic-only',a.prepared,a.out,layer_keys)
 except (ValueError,KeyError,IndexError,TypeError):checks.append(name);return
 raise AssertionError('accepted '+name)
reject('wrong run',lambda q:q.update(runId='other'))
reject('wrong schema',lambda q:q.update(schemaVersion='mi3d-user-entry-v1'))
reject('wrong source',lambda q:q.update(inputSha256='0'*64))
reject('no menu',lambda q:q.update(menuInvoked=False))
reject('no chooser',lambda q:q.update(realChooserApproved=False))
reject('wrong dialog',lambda q:q.update(confirmation='6 个网格'))
reject('wrong undo',lambda q:q.update(historyAfterUndo=1))
reject('redo changed',lambda q:q['afterRedo']['parts'].append(['foreign','part','']))
reject('partial charts',lambda q:q['imported']['meshes'].pop())
reject('source geometry mismatch',lambda q:q['imported']['meshes'][0].update(geometrySha256='0'*64))
reject('wrong local hash',lambda q:q['imported']['meshes'][0].update(localSha256='0'*64))
reject('wrong native form count',lambda q:q['imported']['meshes'][0].update(formCount=288))
reject('missing key',lambda q:q['imported']['meshes'][0]['axes'][0].update(keys=[-30,0,30]))
reject('wrong order',lambda q:q['imported']['meshes'][0]['forms'][0].update(drawOrder=999))
reject('layer form alias',lambda q:q['imported']['meshes'][0]['forms'][1].update(guid='f-0-0'))
reject('partial tuple',lambda q:q['imported']['meshes'][0]['forms'][0]['coordinates'].pop())
reject('wrong Warp control hash',lambda q:q['imported']['deformers'][0]['forms'][0].update(xySha256='0'*64))
reject('render not captured',lambda q:q['sourceTextureRender'].update(status='RUNNING'))
reject('render not restored',lambda q:q['sourceTextureRender'].update(restored=False))
reject('partial frames',lambda q:q['sourceTextureRender']['frames'].pop())
reject('render history changed',lambda q:q['sourceTextureRender']['frames'][0]['historyAfter'].update(position=2))
reject('wrong PNG hash',lambda q:q['sourceTextureRender']['frames'][0].update(artifactSha256='0'*64))
reject('stale pass',lambda q:q['sourceTextureRender']['frames'][0]['lastObservation'][0]['consumed'].update(version=0))
reject('wrong parameter pose',lambda q:q['sourceTextureRender']['frames'][0]['lastObservation'][0]['live']['values'][0].__setitem__(2,0))
for directory,name in [('apple-locals','extra.xy'),('source-texture-render','extra.png')]:
 f=a.out/directory/name;f.write_bytes(b'not-native')
 try:v['verify'](r,'synthetic-only',a.prepared,a.out,layer_keys)
 except ValueError:checks.append('extra '+directory)
 else:raise AssertionError('extra inventory accepted')
 f.unlink()
# Keep hash metadata internally consistent so the rejection must inspect native local coordinates.
f=a.out/'apple-locals/0.xy';raw=f.read_bytes();changed=np.frombuffer(raw,dtype='>f4').copy();changed[0]+=0.01;f.write_bytes(changed.tobytes());q=copy.deepcopy(r);q['layerLocalCaptures'][0]['sha256']=q['imported']['meshes'][0]['localSha256']=sha(changed.tobytes());q['afterRedo']=copy.deepcopy(q['imported'])
try:v['verify'](q,'synthetic-only',a.prepared,a.out,layer_keys)
except ValueError:checks.append('local corruption with consistent hashes')
else:raise AssertionError('corrupt local admitted')
f.write_bytes(raw)
(a.out/'tests.json').write_text(json.dumps(dict(status='PASS_SYNTHETIC_CHECKER_ONLY',positive='blank synthetic PNGs; no native or pixel acceptance',negativeCount=len(checks),negativeCases=checks,hostExecuted=False,goalComplete=False),indent=2)+'\n');print('PASS',len(checks),'synthetic rejection cases; NOT_HOST_VERIFIED')
