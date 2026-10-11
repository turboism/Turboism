#!/usr/bin/env python3
"""Full frozen canonical apple controls/source coordinates; capture integrity only."""
import hashlib,json,math,runpy,struct
from pathlib import Path
import numpy as np
SOURCE_SHA='3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999'
PREPARED={
 'packed.bin':(7715124,'24fa49be32fd2c6a753ad80d7f52f933c523ae047971f3b894c3312690296541'),
 'controls-xy-f32.bin':(66308160,'080548bb25b3f79dd39383d97a25d80cddddb4d0169834ad311dce268a62362d'),
 'orders-i32.bin':(113288,'727d4ccefb2449cfe15c302c50f0fc1ec62945dd70fef709c5a07722f6f0fa4d'),
 'attributed.bin':(8443916,'e104cdf12c789927d1b23bd36fe851c612c398e21af005c22d03e87ec4cb47bb')}
KEYS=[-30+3.75*i for i in range(17)]
POSES=[(y,p) for y in (-30,0,30) for p in (-30,0,30)]+[(-13.125,9.375),(16.875,-24.375)]
YAW='ParamMI3DCanonicalYaw';PITCH='ParamMI3DCanonicalPitch'
def require(ok,why):
 if not ok:raise ValueError(why)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def prepared(directory):
 raw={}
 for name,(size,digest) in PREPARED.items():
  file=directory/name;require(file.is_file() and not file.is_symlink() and file.stat().st_size==size,'bounded frozen prepared '+name)
  raw[name]=file.read_bytes();require(sha(raw[name])==digest,'independent prepared hash '+name)
 packed=raw['packed.bin'];offset=0
 def read(dtype,count,shape=None):
  nonlocal offset
  a=np.frombuffer(packed,dtype,count=count,offset=offset);offset+=a.nbytes
  return a if shape is None else a.reshape(shape)
 require(tuple(read('>i4',3))==(238,119,98),'prepared source header');meshes=[]
 for mesh in range(98):
  n,t=map(int,read('>i4',2));require(0<n<=1609 and t>0,'source mesh domain')
  xyz=read('>f4',n*3,(n,3));uv=read('>f4',n*2,(n,2));local=read('>f4',n*2,(n,2));identity=read('>i4',n*3,(n,3));ix=read('>i4',t*3,(t,3));refs=read('>i4',t*2,(t,2))
  image=np.empty((n,2),dtype='>f4');image[:,0]=identity[:,0]*512+1+uv[:,0].astype(float)*256;image[:,1]=1+uv[:,1].astype(float)*128
  shader=(image.astype(np.float32)/np.array([1024,256],dtype=np.float32)).astype('>f4')
  meshes.append({'xyz':xyz.astype(float),'uv':uv.astype(float),'indices':ix.astype(int),'local':local.astype(float),'triangles':t,'count':n,'sourceMeshSha256':sha(image.tobytes()+shader.tobytes()+ix.tobytes())})
 residual=float(read('>f8',1)[0]);controls=read('>f8',28680*3,(28680,3));require(offset==len(packed) and math.isfinite(residual),'prepared source footer')
 xy=memoryview(raw['controls-xy-f32.bin']);forms={(y,p):sha(xy[i*28680*8:(i+1)*28680*8]) for i,(y,p) in enumerate((y,p) for y in KEYS for p in KEYS)}
 return meshes,forms

def source_projection(xyz,pose):
 y,p=np.deg2rad(pose);cy,sy,cp,sp=math.cos(y),math.sin(y),math.cos(p),math.sin(p);q=xyz-np.array([0,130.6125549674034,0]);yp=cp*q[:,1]-sp*q[:,2];zp=sp*q[:,1]+cp*q[:,2]
 return np.column_stack((512+2.954462184876577*(cy*q[:,0]+sy*zp),512-2.954462184876577*yp))

def verify(report,run_id,directory,captures,pixels):
 meshes,controls=prepared(directory)
 require(report['runId']==run_id and report['mi3dMode']=='canonical-warp-layered-textured' and report['status']=='PASS' and report['failure'] is None,'canonical run/result')
 require(report['finallyRestored'] is True and report['initialCounts']==report['finallyCounts'],'final native source counts')
 require(len(report['checks'])==2 and {c['id'] for c in report['checks']}=={'newCommand','newIdentity'} and all(c['status']=='PASS' and c['actual'] is True for c in report['checks']),'new task document')
 s=report['canonicalWarp'];require(s['status']=='PASS' and s['scope']=='CANONICAL_LAYERED_GLB_SOURCE_WARP_GEOMETRY_AND_CAPTURE_ONLY' and s['visibilityAcceptance'] is False,'canonical capture-only scope')
 require(s['sourceSha256']==SOURCE_SHA and s['vertices']==[m['count'] for m in meshes] and s['triangles']==[m['triangles'] for m in meshes],'canonical source identity/counts')
 require(s['camera']=={'center':[512,897.8898545213067],'scale':2.954462184876577,'pivot':[0,130.6125549674034,0]} and abs(s['idealErrorBoundPixels']-0.6426009706290593)<1e-12,'source framing and actual ideal bound')
 require(s['stages']==['IDENTITY','MATERIAL','ATTACHED','NEUTRAL','BOUND','BAKED'] and s['assemblyOutcome']=='COMMITTED','canonical production stages')
 require(math.isfinite(s['maximumParentLocalDrift']) and 0<=s['maximumParentLocalDrift']<=1e-6
  and math.isfinite(s['parentRoundTripErrorBoundPixels']) and s['parentRoundTripErrorBoundPixels']>=0
  and math.isfinite(s['totalErrorBoundPixels']) and s['totalErrorBoundPixels']<=1
  and abs(s['totalErrorBoundPixels']-s['idealErrorBoundPixels']-s['parentRoundTripErrorBoundPixels'])<1e-12,'bounded native parent round-off')
 require(s['sourceMeshSha256']==[m['sourceMeshSha256'] for m in meshes],'native source image geometry/UV/indices identity')
 require(s['storedForms']==289 and s['controlPointsPerForm']==28680 and len(s['forms'])==289,'complete authored controls')
 require(len(s['axes'])==2,'two independent native axes');axes={a['id']:a for a in s['axes']}
 require(set(axes)=={YAW,PITCH} and all(a['keys']==KEYS and isinstance(a['guid'],str) and a['guid'] for a in axes.values()),'native axis identities/keys')
 guid_to_id={a['guid']:k for k,a in axes.items()};require(len(guid_to_id)==2,'unique native axis GUIDs');seen=set();guids=set()
 for f in s['forms']:
  coords={guid_to_id[g]:v for g,v in f['coordinates']};require(len(coords)==len(f['coordinates'])==2,'complete authored GUID coordinates')
  pose=(coords[YAW],coords[PITCH]);require(isinstance(f['guid'],str) and f['guid'] and f['guid'] not in guids and pose not in seen and controls.get(pose)==f['xySha256'],'full independent stored form hash')
  guids.add(f['guid']);seen.add(pose)
 require(seen==set(controls),'complete joint grid')
 orders=np.frombuffer((directory/'orders-i32.bin').read_bytes(),dtype='>i4').reshape(289,98)
 require(len(s['layerForms'])==98,'complete layer form inventory');sources=set();ids=set();drift=0
 for i,layer in enumerate(s['layerForms']):
  require(layer['mesh']==i and isinstance(layer['sourceGuid'],str) and layer['sourceGuid'] and layer['sourceGuid'] not in sources and isinstance(layer['artMeshId'],str) and layer['artMeshId'] and layer['artMeshId'] not in ids,'distinct ordered native layers')
  sources.add(layer['sourceGuid']);ids.add(layer['artMeshId'])
  require(layer['axes']==s['axes'] and len(layer['forms'])==289,'shared complete layer axes/forms')
  name=f'canonical-warp-capture/layer-local-{i}.xy';require(layer['localCapture']==name and name in captures,'layer local capture identity')
  raw=captures[name];require(len(raw)==meshes[i]['count']*8 and sha(raw)==layer['localSha256'],'layer local bytes/hash')
  local=np.frombuffer(raw,dtype='>f4').reshape(-1,2).astype(float);require(np.all(np.isfinite(local)),'finite native layer local')
  delta=float(np.linalg.norm(local-meshes[i]['local'],axis=1).max());require(delta<=1e-6,'layer local round trip');drift=max(drift,delta)
  seen_layer=set()
  for f in layer['forms']:
   coords={guid_to_id[g]:v for g,v in f['coordinates']};require(len(coords)==len(f['coordinates'])==2,'complete layer GUID tuple')
   pose=(coords[YAW],coords[PITCH]);require(pose in controls and pose not in seen_layer and isinstance(f['guid'],str) and f['guid'] and f['guid'] not in guids,'unique complete layer form identity')
   index=KEYS.index(pose[0])*17+KEYS.index(pose[1]);require(type(f['drawOrder']) is int and f['drawOrder']==int(orders[index,i]),'independent layer key order')
   seen_layer.add(pose);guids.add(f['guid'])
  require(seen_layer==set(controls),'complete layer joint grid')
 require(abs(drift-s['maximumParentLocalDrift'])<1e-12,'independent maximum parent local drift')
 require(s['historyAfter']['position']==s['historyBefore']['position']+1 and s['historyAfterUndo']['position']==s['historyBefore']['position'] and s['historyAfterRedo']['position']==s['historyAfter']['position'],'single root native Undo/Redo history')
 require(s['failureOutcome']=='ROLLED_BACK' and s['failureBefore']['position']==s['failureAfter']['position']==s['historyAfter']['position'],'failed actual form write rollback')
 require(all(s[k] is True for k in ('failedWriteRollbackExact','authoredFormsAfterSamplingExact','undoInventoryExact','redoSourceMeshesWarpImagesExact')),'source/control/image/inventory protections')
 require(set(s['baseline'])=={'meshes','deformers','parts','parameters','images'},'complete baseline identity inventory')
 require(len(s['samples'])==1078 and {(tuple(r['pose']),r['mesh']) for r in s['samples']}=={(p,i) for p in POSES for i in range(98)},'pose/source mesh inventory')
 require(set(captures)=={f'canonical-warp-capture/sample-{i}.xy' for i in range(1078)} | {f'canonical-warp-capture/layer-local-{i}.xy' for i in range(98)},'exact native coordinate capture inventory');maximum=0
 for index,r in enumerate(s['samples']):
  require(r['capture']==f'canonical-warp-capture/sample-{index}.xy' and r['coordinateType']=='Canvas','capture identity/space')
  raw=captures[r['capture']];mesh=meshes[r['mesh']];require(len(raw)==mesh['count']*8 and sha(raw)==r['xySha256'],'capture length/hash')
  points=np.frombuffer(raw,dtype='>f4').reshape(-1,2).astype(float);require(np.all(np.isfinite(points)),'finite native coordinates')
  error=float(np.linalg.norm(points-source_projection(mesh['xyz'],r['pose']),axis=1).max())
  require(error<=1 and math.isfinite(r['maxErrorPixels']) and abs(error-r['maxErrorPixels'])<.0001,'independent all-source projection');maximum=max(maximum,error)
  for family in ('live','consumed'):
   values={v['id']:v['value'] for v in r[family]['parameters']};require(len(values)==len(r[family]['parameters']) and values[YAW]==r['pose'][0] and values[PITCH]==r['pose'][1],'exact native parameter '+family)
 require(len(s['pixelFrames'])==11 and set(pixels)=={f'canonical-warp-pixels/{i}.png' for i in range(11)},'exact native PNG inventory')
 decode=runpy.run_path(str(Path(__file__).resolve().parents[1]/'model-import-3d-host-probe/verify-pixel-sampling.py'))['_pixels']
 for i,(frame,pose) in enumerate(zip(s['pixelFrames'],POSES)):
  name=f'canonical-warp-pixels/{i}.png';raw=pixels[name]
  require(frame['artifact']==name and tuple(frame['pose'])==pose and frame['artifactSha256']==sha(raw) and frame['artifactBytes']==len(raw),'PNG source pose/bytes/hash')
  require(frame['status']=='CAPTURED_NATIVE_NOT_YET_INDEPENDENTLY_VERIFIED' and frame['historyBefore']==frame['historyAfter'],'render history/capture-only scope');decode(raw,size=1024)
 return {'scope':s['scope'],'maxIndependentErrorPixels':maximum,'productionImportAccepted':False,'visibilityAccepted':False}
