#!/usr/bin/env python3
"""Independent original-GLB projection for all native Cat partition slots; finite poses."""
import argparse, hashlib, json, struct
from pathlib import Path
import numpy as np
SOURCE_SHA='4d0688298a366bfd5501918a7afac5dfd4f6aa35445a4f73077435045ba02ee7'
GROUPS_SHA='3b643a206910afa0f644450aadd9e509beb61871d10861b31a2e971cb63fa6dc'
THRESHOLD=5
POSES=[[0,0,0],[-180,-180,-180],[180,180,180],[90,90,90],[35,-47,61],[-127,103,-155],[37.5,-112.5,23],[-150,75,-72],[-179.5,179.5,179.5]]

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def require(ok,why):
 if not ok:raise ValueError(why)

def measure(report_path,source,groups_path,out):
 require(sha(source)==SOURCE_SHA and sha(groups_path)==GROUPS_SHA,'frozen source and partition identity')
 report=json.loads(report_path.read_text())
 require(report['status']=='PASS' and report['inputSha256']==SOURCE_SHA,'completed Cat report')
 require(report['schemaVersion'] in ('mi3d-cat-vertex-menu-v1','mi3d-cat-vertex-frames-v1','mi3d-cat-vertex-reopen-v1'),'Cat observation scope')
 require(len(report['frames'])==9 and [f['XYZ'] for f in report['frames']]==POSES,'exact nine native poses')
 raw=source.read_bytes();n=struct.unpack_from('<I',raw,12)[0];doc=json.loads(raw[20:20+n]);binary=raw[28+n:]
 require(doc['nodes']==[{'mesh':0,'name':'Cat'}],'exact source node without transform')
 def array(index,dim,dtype,component):
  a=doc['accessors'][index];v=doc['bufferViews'][a['bufferView']]
  require(a['componentType']==component and 'byteStride' not in v and 'sparse' not in a,'source accessor layout')
  return np.frombuffer(binary,dtype=dtype,count=a['count']*dim,offset=v.get('byteOffset',0)+a.get('byteOffset',0)).reshape(-1,dim)
 primitive=doc['meshes'][0]['primitives'][0]
 points=array(primitive['attributes']['POSITION'],3,'<f4',5126).astype(float)
 triangles=array(primitive['indices'],1,'<u4',5125).reshape(-1,3)

 require(points.shape==(36530,3) and triangles.shape==(70576,3),'all source slots/faces')
 groups=np.fromfile(groups_path,dtype='>i4');require(groups.shape==(70576,) and set(groups)==set(range(512)),'complete partition')
 slots=[]
 for group in range(512):
  ordered=dict.fromkeys(int(i) for i in triangles[groups==group].reshape(-1))
  slots.append(points[list(ordered)])
 require(sum(map(len,slots))==48497,'all partition slots')
 pivot=(points.min(axis=0)+points.max(axis=0))/2;scale=400/np.linalg.norm(points-pivot,axis=1).max()
 metrics=[]
 for frame in report['frames']:
  actual={r['artMeshId']:r for r in frame['evaluated']}
  require(len(actual)==len(frame['evaluated'])==512 and set(actual)=={f'ArtMesh3D{i}' for i in range(512)},'native partition identities')
  pitch,yaw,roll=np.deg2rad(frame['XYZ']);cp,sp=np.cos(pitch),np.sin(pitch);cy,sy=np.cos(yaw),np.sin(yaw);cr,sr=np.cos(roll),np.sin(roll)
  errors=[]
  for i,source_points in enumerate(slots):
   row=actual[f'ArtMesh3D{i}'];require(row['live']['values']==row['consumed']['values'],'native consumed pose')
   values={r[1]:r[2] for r in row['consumed']['values']}
   for label,wanted in zip(('Pitch','Yaw','Roll'),frame['XYZ']):
    require([v for n,v in values.items() if n.startswith('Param3D'+label)]==[wanted],'requested XYZ consumption')
   calculated=row['calculated'];require(calculated['coordinateType']=='Canvas','world canvas coordinates')
   observed=np.asarray(calculated['positions']);require(observed.shape==(len(source_points),2) and np.isfinite(observed).all(),'all finite native slots')
   x,y,z=(source_points-pivot).T;yp=cp*y-sp*z;zp=sp*y+cp*z;xp=cy*x+sy*zp
   expected=np.column_stack((512+scale*(cr*xp-sr*yp),512-scale*(sr*xp+cr*yp)))
   errors.append(np.linalg.norm(observed-expected,axis=1))
  error=np.concatenate(errors);metrics.append(dict(poseXYZ=frame['XYZ'],samples=len(error),maxPixels=float(error.max()),meanPixels=float(error.mean()),over5Pixels=int((error>THRESHOLD).sum())))
 maximum=max(r['maxPixels'] for r in metrics)
 result=dict(status='INDEPENDENT_ORIGINAL_GLB_NINE_XYZ_POSITION_MEASUREMENT',nativeReportSha256=sha(report_path),sourceSha256=SOURCE_SHA,groupsSha256=GROUPS_SHA,allPartitionSlots=True,framingDerivedFromOriginalGLB=True,pivot=pivot.tolist(),scale=float(scale),thresholdPixels=THRESHOLD,thresholdOrigin='Existing full-circle direct authoring 5px bound; not changed after measurement',maximumPixels=maximum,withinExistingFivePixelBudget=maximum<=THRESHOLD,frames=metrics,occlusionAccepted=False,continuousDomainAccepted=False)
 require(not out.exists(),'new output required');out.write_text(json.dumps(result,indent=2)+'\n')
 require(maximum<=THRESHOLD,'native projection exceeds existing five-pixel budget')
 print(f"PASS {sum(r['samples'] for r in metrics)} source-slot samples, maximum {maximum:.9f}px")

if __name__=='__main__':
 p=argparse.ArgumentParser()
 for name in ('report','source','groups','out'):p.add_argument('--'+name,type=Path,required=True)
 a=p.parse_args();measure(a.report,a.source,a.groups,a.out)
