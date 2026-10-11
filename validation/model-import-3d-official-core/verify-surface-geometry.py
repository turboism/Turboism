"""Fixed-source full dense plane/seam Core geometry; process and pixels separate."""
import math,runpy
from pathlib import Path
BASE=runpy.run_path(str(Path(__file__).resolve().parents[1]/'model-import-3d-user-entry/verify-surface-menu.py'))
INVENTORY=runpy.run_path(str(Path(__file__).resolve().parents[1]/'model-import-3d-host-probe/verify-surface-geometry.py'))['inventory']
def require(ok,why):
 if not ok:raise ValueError('SURFACE_CORE: '+why)
def f32(v):return BASE['G']['f32'](v)
def finite(v):return type(v) in (int,float) and math.isfinite(v)

def verify(capture,glb,packed,axes):
 require(capture['schemaVersion']=='mi3d-surface-official-core-capture-v1' and capture['status']=='CAPTURED_NOT_VERIFIED'
         and capture['poseProtocol']=='mi3d-surface-dense-9921-v1','explicit dense surface profile')
 require(capture['canvas']==dict(size=[1024,1024],origin=[512,512],pixelsPerUnit=100),'fixed export canvas/origin/units')
 _,vertices,_=BASE['sources'](glb);meshes=packed['meshes'];require(len(meshes)==len(vertices) in (1,2),'complete admitted source')
 keys,poses=INVENTORY(48);require(keys==BASE['KEYS'],'independent key protocols agree')
 require(capture['axes']==axes and len(set(axes))==2 and {a['id'] for a in meshes[0]['axes']}==set(axes),'axes from admitted export')
 authored={p[0]:p for p in packed['parameters']};p=capture['parameters'];ids=p['ids']
 require(len(authored)==len(packed['parameters'])==len(ids)==len(set(ids)) and set(ids)==set(authored),'full admitted parameter inventory')
 require(all(len(p[k])==len(ids) for k in ('minimum','maximum','default','keyCounts','keys','repeats')),'full parameter arrays')
 for i,name in enumerate(ids):
  a=authored[name];require([p[k][i] for k in ('minimum','default','maximum')]==a[1:4] and a[4]==a[2] and p['repeats'][i] is False,'fixed parameter domains/default/repeat')
  expected=keys if name in axes else [];require(type(p['keyCounts'][i]) is int and p['keyCounts'][i]==len(expected) and list(map(f32,p['keys'][i]))==expected,'complete dense or unused parameter keys')
 frames=capture['frames'];require(len(frames)==len(poses)==9921,'all keys/cells/edge midpoints/random poses')
 expected_ids=[m['artMeshId'] for m in meshes];require(len(set(expected_ids))==len(meshes),'admitted distinct mesh IDs')
 maximum=0.;invariants={}
 for frame,pose in zip(frames,poses):
  require(list(map(f32,frame['pose']))==list(pose),'ordered float32 pose inventory')
  require(len(frame['values'])==len(ids) and all(finite(v) for v in frame['values']),'complete finite values')
  wanted={a[0]:a[2] for a in packed['parameters']};wanted.update(zip(axes,pose))
  require(all(f32(v)==f32(wanted[name]) for name,v in zip(ids,frame['values'])),'actual parameter pose/default values')
  rows=frame['drawables'];require(len(rows)==len(meshes) and len({r['id'] for r in rows})==len(rows),'complete Core drawable inventory')
  by_id={r['id']:r for r in rows};require(set(by_id)==set(expected_ids) and all(type(r['renderOrder']) is int for r in rows) and sorted(r['renderOrder'] for r in rows)==list(range(len(rows))),'admitted mesh IDs/render permutation')
  for mesh,points in zip(meshes,vertices):
   row=by_id[mesh['artMeshId']];n=len(points);require(type(row['vertexCount']) is int and row['vertexCount']==n,'source slots not welded')
   indices=[v for i in range(0,len(mesh['indices']),3) for v in reversed(mesh['indices'][i:i+3])]
   uv=[v for xy in mesh['uvs'] for v in (f32(xy[0]),f32(1-f32(xy[1])))]
   require(row['indices']==indices and list(map(f32,row['uvs']))==uv,'full fixed source topology/atlas UVs')
   require(all(type(row[k]) is int for k in ('constantFlags','blendMode','textureIndex','drawOrder')) and row['constantFlags']==0 and row['blendMode']==0 and row['textureIndex']==0 and row['masks']==[] and row['drawOrder']==500,'fixed culling/blend/texture/masks/draw order')
   require(finite(row['opacity']) and abs(row['opacity']-1)<=4*2**-23,'opacity float32 weight bound')
   require(len(row['multiplyColor'])==len(row['screenColor'])==4 and all(finite(v) and abs(v-t)<=4*2**-23 for k,target in [('multiplyColor',[1]*4),('screenColor',[0,0,0,1])] for v,t in zip(row[k],target)),'unmodified tint float32 weight bound')
   fixed={k:row[k] for k in ('indices','uvs','constantFlags','textureIndex','masks','vertexCount','drawOrder')}
   require(row['id'] not in invariants or invariants[row['id']]==fixed,'stable source topology/UV across all poses');invariants[row['id']]=fixed
   xy=row['positions'];require(len(xy)==2*n and all(finite(v) for v in xy),'complete finite Core positions')
   canvas=[[512+100*f32(xy[2*i]),512-100*f32(xy[2*i+1])] for i in range(n)]
   error=BASE['G']['error'](canvas,BASE['truth'](points,*pose));require(error<=.1,f'{row["id"]} at {pose}: {error}px exceeds 0.1')
   maximum=max(maximum,error)
 return dict(status='FULL_SURFACE_DENSE_CORE_GEOMETRY_CHECKED_NOT_PROCESS_OR_RENDERER_ACCEPTANCE',poses=9921,meshes=len(meshes),sourceVertexSlots=sum(map(len,vertices)),maxErrorCanvasPixels=maximum,toleranceCanvasPixels=.1,officialExportAccepted=False,goalComplete=False)
