#!/usr/bin/env python3
"""Independent 9921-pose geometry protocol counterexamples, no native execution."""
import argparse,copy,json,runpy
from pathlib import Path
V=runpy.run_path(str(Path(__file__).with_name('verify-surface-geometry.py')))
p=argparse.ArgumentParser();p.add_argument('--export-artifacts',type=Path,required=True);a=p.parse_args()
r=json.loads((a.export_artifacts/'report.json').read_text());packed=json.loads(r['officialExport']['packedStateJson']);glb=a.export_artifacts/'authorized-surface.glb';axes=r['sourceTextureRender']['axes']
keys,poses=V['INVENTORY'](48);ids=[p[0] for p in packed['parameters']];_,points,_=V['BASE']['sources'](glb)
parameters=dict(ids=ids,minimum=[p[1] for p in packed['parameters']],default=[p[2] for p in packed['parameters']],maximum=[p[3] for p in packed['parameters']],
                repeats=[False]*len(ids),keyCounts=[49 if p in axes else 0 for p in ids],keys=[keys if p in axes else [] for p in ids])
frames=[]
for pose in poses:
 values=[p[2] for p in packed['parameters']]
 for axis,v in zip(axes,pose):values[ids.index(axis)]=v
 rows=[]
 for i,(mesh,source) in enumerate(zip(packed['meshes'],points)):
  xy=V['BASE']['truth'](source,*pose)
  rows.append(dict(id=mesh['artMeshId'],vertexCount=len(source),positions=[V['f32'](v) for x,y in xy for v in ((x-512)/100,(512-y)/100)],
       indices=[v for k in range(0,len(mesh['indices']),3) for v in reversed(mesh['indices'][k:k+3])],
       uvs=[v for u,w in mesh['uvs'] for v in (V['f32'](u),V['f32'](1-V['f32'](w)))],constantFlags=0,blendMode=0,textureIndex=0,masks=[],drawOrder=500,renderOrder=i,opacity=1,multiplyColor=[1]*4,screenColor=[0,0,0,1]))
 frames.append(dict(pose=list(pose),values=values,drawables=rows))
capture=dict(schemaVersion='mi3d-surface-official-core-capture-v1',status='CAPTURED_NOT_VERIFIED',poseProtocol='mi3d-surface-dense-9921-v1',
             canvas=dict(size=[1024,1024],origin=[512,512],pixelsPerUnit=100),axes=axes,parameters=parameters,frames=frames)
positive=V['verify'](capture,glb,packed,axes);checks=0
def reject(c):
 global checks
 try:V['verify'](c,glb,packed,axes)
 except (ValueError,KeyError):checks+=1;return
 raise AssertionError('corruption accepted')
for field,value in [('schemaVersion','mi3d-official-core-capture-v2'),('poseProtocol','cube-377-v1'),('canvas',dict(size=[1024,1024],origin=[0,0],pixelsPerUnit=100)),('axes',axes[::-1])]:
 c=copy.deepcopy(capture);c[field]=value;reject(c)
for field,value in [('ids',ids+['foreign']),('keyCounts',[0]*len(ids)),('keys',[[] for _ in ids]),('repeats',[True]*len(ids))]:
 c=copy.deepcopy(capture);c['parameters'][field]=value;reject(c)
for field,value in [('pose',[0,0]),('values',[0]),('drawables',[])]:
 c=copy.deepcopy(capture);c['frames'][0][field]=value;reject(c)
for field,value in [('id','foreign'),('vertexCount',0),('indices',[]),('uvs',[]),('positions',[]),('textureIndex',1),('masks',[0]),('drawOrder',499),('blendMode',1),('constantFlags',False),('renderOrder',False),('opacity',.5),('multiplyColor',[.5]*4)]:
 c=copy.deepcopy(capture);c['frames'][0]['drawables'][0][field]=value;reject(c)
# Missing a midpoint, boundary, or random sample must fail the complete ordered inventory.
for index in (1,96,len(poses)-1):
 c=copy.deepcopy(capture);del c['frames'][index];reject(c)
# Keep the correct count but perturb a midpoint and the final random evaluated position.
for index in (1,len(poses)-1):
 c=copy.deepcopy(capture);c['frames'][index]['drawables'][0]['positions'][0]+=.002;reject(c)
print(json.dumps(dict(status='SYNTHETIC_DENSE_SURFACE_CORE_REFUSALS_PASS',refusals=checks,positive=positive,nativeExecuted=False)))
