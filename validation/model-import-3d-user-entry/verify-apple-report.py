#!/usr/bin/env python3
"""Strict source/layout/menu/history gate for the layered apple UI capture; no process or export claim."""
import argparse,hashlib,json,re,runpy
from pathlib import Path
import numpy as np
C=runpy.run_path(str(Path(__file__).resolve().parents[1]/'model-import-3d-canonical-warp/verify-canonical-warp-layered.py'))
POSES=C['POSES'];KEYS=C['KEYS'];SOURCE_SHA=C['SOURCE_SHA']
def require(ok,why):
 if not ok:raise ValueError('APPLE_UI: '+why)
def sha(raw):return hashlib.sha256(raw).hexdigest()
def verify(r,run,prepared,artifacts,layer_keys=None):
 layer_keys=KEYS if layer_keys is None else layer_keys
 require(layer_keys in (KEYS,KEYS[::2]),"explicit admitted layer grid")
 layer_forms=len(layer_keys)**2
 meshes,controls=C['prepared'](prepared);orders=np.frombuffer((prepared/'orders-i32.bin').read_bytes(),dtype='>i4').reshape(289,98)
 require(r['schemaVersion']=='mi3d-apple-user-entry-v1' and r['runId']==run and r['scope']=='REAL_MENU_IMPORT_UNDO_REDO_ONLY' and r['status']=='PASS' and 'failure' not in r,'report run/scope/status')
 require(r['inputSha256']==SOURCE_SHA and r['seedDocumentId'] and r['targetDocumentId'] and r['seedDocumentId']!=r['targetDocumentId'],'source/task identity')
 require(r['menuInvoked'] is True and r['realChooserApproved'] is True and r['appearance']=='SOURCE_MATERIAL' and all(v in r['confirmation'] for v in ('98 个曲面网格','17 / 17')) and r['completionMessage'].startswith('导入完成'),'actual production dialogs')
 base,imported=r['baseline'],r['imported'];require(r['undoRestored'] is True and r['afterUndo']==base and r['redoRestored'] is True and r['afterRedo']==imported,'full observed Undo/Redo state')
 before=r['historyBefore'];require(type(before) is int and before>=0 and r['historyAfter']==r['historyAfterRedo']==before+1 and r['historyAfterUndo']==before,'single root history')
 require(all(base[k]==[] for k in ('meshes','meshMetadata','rawImages','imageGroups','deformers')),'blank baseline')
 original={p[0]:p for p in base['parameters']};parameters={p[0]:p for p in imported['parameters']};require(len(original)==len(base['parameters']) and len(parameters)==len(imported['parameters']) and all(parameters.get(k)==v for k,v in original.items()),'baseline parameter protection')
 added=set(parameters)-set(original);yaw=[k for k in added if re.fullmatch('Param3DYaw[0-9a-f]{12}',k)];pitch=[k for k in added if re.fullmatch('Param3DPitch[0-9a-f]{12}',k)]
 require(len(added)==2 and len(yaw)==len(pitch)==1 and yaw[0][-12:]==pitch[0][-12:] and all(parameters[k][1:]==[-30,0,30,0] for k in added),'generated axis definitions')
 snapshots=imported['meshes'];require(len(snapshots)==98 and len({m['id'] for m in snapshots})==98 and len({m['sourceGuid'] for m in snapshots})==98,'complete unique native charts')
 by_geometry={m['sourceMeshSha256']:i for i,m in enumerate(meshes)};require(len(by_geometry)==98,'independent source chart identities')
 seen=set();forms=set();axes=snapshots[0]['axes'];require(len(axes)==2 and {a['id'] for a in axes}==added and all(a['keys']==layer_keys for a in axes) and len({a['guid'] for a in axes})==2,'shared dense axis definitions')
 guid_to_id={a['guid']:a['id'] for a in axes};capture_rows=r['layerLocalCaptures'];require(len(capture_rows)==98 and {p.name for p in (artifacts/'apple-locals').iterdir()}=={f'{i}.xy' for i in range(98)},'exact local file inventory')
 def pose_of(f):
  coordinates={guid_to_id[g]:v for g,v in f['coordinates']};require(len(coordinates)==len(f['coordinates'])==2,'full native GUID tuple');return coordinates[yaw[0]],coordinates[pitch[0]]
 maximum=0
 for i,m in enumerate(snapshots):
  require(m['geometrySha256'] in by_geometry,'full source image geometry/UV/indices hash');index=by_geometry[m['geometrySha256']];expected=meshes[index]
  require(index not in seen,'duplicated source chart');seen.add(index)
  require(m['vertexCount']==expected['count'] and m['triangleCount']==expected['triangles'] and m['axisCount']==2 and m['formCount']==m['cellCount']==layer_forms and m['axes']==axes,'complete native chart layout')
  require(re.fullmatch('[0-9a-f]{64}',m['authoringSha256']) is not None,'full native authoring digest')
  capture=capture_rows[i];name=f'apple-locals/{i}.xy';file=artifacts/name;require(capture['id']==m['id'] and capture['artifact']==name and not file.is_symlink(),'local artifact identity')
  raw=file.read_bytes();require(len(raw)==expected['count']*8 and sha(raw)==capture['sha256']==m['localSha256'],'native common local bytes/hash')
  local=np.frombuffer(raw,dtype='>f4').reshape(-1,2).astype(float);require(np.all(np.isfinite(local)),'finite native local coordinates');delta=float(np.linalg.norm(local-expected['local'],axis=1).max());require(delta<=1e-6,'bounded native local round trip');maximum=max(maximum,delta)
  poses=set();require(len(m['forms'])==layer_forms,'all layer form cells')
  for f in m['forms']:
   pose=pose_of(f);require(pose in controls and pose not in poses and isinstance(f['guid'],str) and f['guid'] and f['guid'] not in forms,'independent form GUID/grid');poses.add(pose);forms.add(f['guid'])
   key=KEYS.index(pose[0])*17+KEYS.index(pose[1]);require(type(f['drawOrder']) is int and f['drawOrder']==int(orders[key,index]),'independent joint depth order')
  require(poses=={(y,p) for y in layer_keys for p in layer_keys},'complete layer joint domain')
 require(seen==set(range(98)),'all source charts')
 warps=imported['deformers'];require(len(warps)==1,'one native Warp');warp=warps[0];require(warp['rows']==119 and warp['columns']==238 and warp['formCount']==289 and warp['axes']==[dict(a,keys=KEYS) for a in axes] and len(warp['forms'])==289 and re.fullmatch('[0-9a-f]{64}',warp['authoringSha256']),'dense native Warp layout')
 poses=set()
 for f in warp['forms']:
  pose=pose_of(f);require(pose in controls and pose not in poses and f['guid'] not in forms and f['xySha256']==controls[pose],'independent native Warp controls');poses.add(pose);forms.add(f['guid'])
 require(poses==set(controls),'complete Warp joint domain')
 metadata=imported['meshMetadata'];require(len(metadata)==98 and [m['id'] for m in metadata]==[m['id'] for m in snapshots] and all(m['culling'] is True and m['visible'] is True and m['input']['kind']=='MODEL_IMAGE' and m['input']['atlas']=='' for m in metadata),'native image binding/visibility')
 images={i[0] for g in imported['imageGroups'] for i in g['images']};bindings={m['input']['image'] for m in metadata};require(len(imported['rawImages'])==len(images)==len(bindings)==1 and images==bindings and all(i[2:]==[1024,256] for i in imported['rawImages']) and all(i[2:]==[1024,256] for g in imported['imageGroups'] for i in g['images']),'one shared source texture image')
 parts={p[0] for p in imported['parts']}-{p[0] for p in base['parts']};require(len(parts)==1 and {m['parent'] for m in metadata}==parts,'one native control part')
 render=r['sourceTextureRender'];require(r['renderVerification']=='CAPTURED_REQUIRES_INDEPENDENT_ORACLE' and render['schemaVersion']=='mi3d-apple-texture-render-v1' and render['status']=='CAPTURED' and render['restored'] is True and render['axes']==[yaw[0],pitch[0]],'native capture/restoration scope')
 protected=json.loads(render['protectedStateJson']);expected_protected=dict(imported);expected_protected['parameters']=[p[:4] for p in imported['parameters']];require(protected==expected_protected and sha(render['protectedStateJson'].encode())==render['protectedStateSha256'],'full protected authored state')
 frames=render['frames'];require(len(frames)==11 and {p.name for p in (artifacts/'source-texture-render').iterdir()}=={f'{i}.png' for i in range(11)},'exact eleven PNG inventory')
 decode=runpy.run_path(str(Path(__file__).resolve().parents[1]/'model-import-3d-host-probe/verify-pixel-sampling.py'))['_pixels']
 for i,(f,pose) in enumerate(zip(frames,POSES)):
  name=f'source-texture-render/{i}.png';file=artifacts/name;require(not file.is_symlink(),'regular native PNG');raw=file.read_bytes()
  require(f['index']==i and tuple(f['pose'])==pose and f['artifact']==name and len(raw)==f['artifactBytes'] and sha(raw)==f['artifactSha256'] and f['waitReason']=='CONSUMED','native PNG pose/bytes/hash/consumption')
  require(f['historyBefore']==f['historyAfter'] and f['stateBeforeSha256']==f['stateAfterSha256']==render['protectedStateSha256'] and f['evaluationBefore']==f['evaluationAfter'],'render protections')
  require(len(f['requested'])==len(f['lastObservation'])==len(f['evaluationBefore'])==98,'full evaluated chart inventory')
  for requested,actual in zip(f['requested'],f['lastObservation']):
   require(requested['artMeshId']==actual['artMeshId'] and requested['sourceGuid']==actual['sourceGuid'] and requested['live']['values']==actual['live']['values']==actual['consumed']['values'] and requested['consumed']['version']!=actual['consumed']['version'],'fresh consumed pose')
   values={v[1]:v[2] for v in actual['live']['values']};require(values[yaw[0]]==pose[0] and values[pitch[0]]==pose[1],'exact live pose')
  decode(raw,size=1024)
 require(render['entryHistory']['position']==render['restoredHistory']['position']==before+1 and render['entryHistory']['document']==render['restoredHistory']['document'] and render['entryHistory']['manager']==render['restoredHistory']['manager'],'capture history target restoration')
 return {'status':'APPLE_MENU_SOURCE_CONTROL_HISTORY_CAPTURE_CHECKED_NOT_PROCESS_OR_PIXEL_ACCEPTANCE','runId':run,'meshes':98,'jointForms':289,'layerForms':layer_forms,'triangles':150784,'nativeFrames':11,'maximumParentLocalDrift':maximum,'goalComplete':False}
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);p.add_argument('--run',required=True);p.add_argument('--prepared',type=Path,required=True);p.add_argument('--artifacts',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
 require(a.report.stat().st_size<=64*1024*1024 and not a.report.is_symlink(),'bounded report')
 def pairs(items):
  r={}
  for k,v in items:require(k not in r,'duplicate JSON key');r[k]=v
  return r
 r=json.loads(a.report.read_bytes(),object_pairs_hook=pairs,parse_constant=lambda v:(_ for _ in ()).throw(ValueError(v)));result=verify(r,a.run,a.prepared,a.artifacts)
 with a.out.open('x') as f:json.dump(result,f,indent=2);f.write('\n')
 print(result['status'])
