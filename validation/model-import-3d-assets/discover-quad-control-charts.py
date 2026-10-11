#!/usr/bin/env python3
"""Read quad-paired GLB triangles, trace regular topology, emit source chart attribution.

Coordinate equality identifies topology only. Rendering slots, UVs and indices are
never merged or replaced. A closed oriented two-manifold and rectangular grid
components are required; ambiguous/periodic/overlapping chart topology is refused.
This diagnostic does not admit native visibility or production imports.
"""
import argparse,json,struct,hashlib
from collections import defaultdict,deque,Counter
from pathlib import Path
import numpy as np

def require(ok,why):
 if not ok:raise ValueError(why)
def edge(a,b):return (min(int(a),int(b)),max(int(a),int(b)))

def discover(xyz,triangles,max_side=32):
 require(len(triangles)%2==0,'complete triangle pairs required')
 a,b=triangles[::2],triangles[1::2]
 require(np.array_equal(a[:,0],b[:,0]) and np.array_equal(a[:,2],b[:,1]),'source triangles must retain quad 0-2 diagonal')
 slots=np.column_stack((a,b[:,2]));_,topology=np.unique(xyz,axis=0,return_inverse=True);quads=topology[slots]
 require(all(len(set(q))==4 for q in quads),'collapsed source quad')
 # Exact position equivalence is solely a topology relation; slot data remains original.
 links=defaultdict(list);neighbors=defaultdict(set);fans=defaultdict(lambda:defaultdict(set));incident=defaultdict(set)
 for f,q in enumerate(quads):
  for i,v in enumerate(q):
   v=int(v);previous=int(q[(i-1)%4]);following=int(q[(i+1)%4]);links[edge(v,following)].append((f,v,following));neighbors[v].add(following);neighbors[v].add(previous)
   fans[v][previous].add(following);fans[v][following].add(previous);incident[v].add(f)
 for entries in links.values():require(len(entries)==2 and entries[0][1:]==entries[1][1:][::-1],'closed oriented two-manifold edge required')
 opposite={}
 for v,fan in fans.items():
  require(len(fan)==len(incident[v]) and all(len(n)==2 for n in fan.values()),'ambiguous/nonmanifold source vertex fan')
  seen=set();todo=[next(iter(fan))]
  while todo:
   n=todo.pop()
   if n in seen:continue
   seen.add(n);todo.extend(fan[n]-seen)
  require(len(seen)==len(fan),'coincident disconnected source vertex fans are unsupported')
  if len(neighbors[v])==4:
   for incoming in neighbors[v]:
    remaining=neighbors[v]-fan[incoming]-{incoming};require(len(remaining)==1,'regular quad fan has no unique opposite edge');opposite[(v,incoming)]=next(iter(remaining))
 extraordinary=[v for v,n in neighbors.items() if len(n)!=4];require(extraordinary,'closed fully regular periodic surfaces need explicit cuts')
 cuts=set();paths=[]
 for start in sorted(extraordinary):
  for first in sorted(neighbors[start]):
   if edge(start,first) in cuts:continue
   previous,current=start,first;walked=set();length=0
   while True:
    state=(previous,current);require(state not in walked,'singularity trace entered a periodic regular loop');walked.add(state);cuts.add(edge(previous,current));length+=1
    if len(neighbors[current])!=4:break
    previous,current=current,opposite[(current,previous)]
   paths.append(length)
 adjacency=[[] for _ in quads]
 for e,entries in links.items():
  if e not in cuts:
   x,y=entries[0][0],entries[1][0];adjacency[x].append((y,entries[1][1],entries[1][2]));adjacency[y].append((x,entries[0][1],entries[0][2]))
 visited=set();chart_ids=np.full(len(quads),-1,dtype=np.int32);parameters=np.empty((len(quads),4,2),dtype=np.float64);charts=[]
 for first in range(len(quads)):
  if first in visited:continue
  component=[];assigned={int(v):p for v,p in zip(quads[first],[(0,0),(1,0),(1,1),(0,1)])};coordinates={};todo=deque([first]);visited.add(first)
  while todo:
   f=todo.popleft();component.append(f)
   for neighbor,u,v in adjacency[f]:
    pu,pv=assigned[u],assigned[v];du,dv=pv[0]-pu[0],pv[1]-pu[1];require(abs(du)+abs(dv)==1,'transported edge is not one regular grid step');left=(-dv,du)
    q=list(map(int,quads[neighbor]));k=q.index(u);require(q[(k+1)%4]==v,'source winding differs across chart transport')
    points=[pu,pv,(pv[0]+left[0],pv[1]+left[1]),(pu[0]+left[0],pu[1]+left[1])]
    for i,p in enumerate(points):
     vertex=q[(k+i)%4]
     if vertex in assigned:require(assigned[vertex]==p,'nonrectangular/periodic chart transport conflicts')
     else:assigned[vertex]=p
    if neighbor not in visited:visited.add(neighbor);todo.append(neighbor)
  require(len(set(assigned.values()))==len(assigned),'chart topology overlaps different source vertices')
  points=np.array(list(assigned.values()));lo=points.min(axis=0);hi=points.max(axis=0);width,height=map(int,hi-lo)
  require(width>0 and height>0 and len(component)==width*height and len(assigned)==(width+1)*(height+1),'chart component is not a full rectangular source grid')
  for f in component:
   p=np.array([assigned[int(v)] for v in quads[f]])-lo;low=p.min(axis=0);high=p.max(axis=0)
   require(np.array_equal(high-low,[1,1]),'source face is not a unit chart quad');position=tuple(low);require(position not in coordinates,'duplicate source chart cell');coordinates[position]=f
  # Split large regular components into bounded rectangles without changing any source faces.
  tile_side=min(max_side,width,height)
  for by in range(0,height,tile_side):
   for bx in range(0,width,tile_side):
    w,h=min(tile_side,width-bx),min(tile_side,height-by);chart=len(charts);faces=[]
    for y in range(by,by+h):
     for x in range(bx,bx+w):
      f=coordinates[(x,y)];faces.append(f);chart_ids[f]=chart;parameters[f]=(np.array([assigned[int(v)] for v in quads[f]])-lo-[bx,by])/[w,h]
    charts.append({'chart':chart,'width':w,'height':h,'quads':len(faces)})
 require(np.all(chart_ids>=0) and len(visited)==len(quads),'incomplete original face attribution')
 tri_chart=np.repeat(chart_ids,2);tri_params=parameters[:,[0,1,2,0,2,3],:].reshape(-1,3,2)
 return tri_chart,tri_params,{'sourceQuads':len(quads),'sourceSlots':len(xyz),'topologyVertices':len(neighbors),'extraordinaryVertices':len(extraordinary),'valences':dict(Counter(map(len,neighbors.values()))),'cutEdges':len(cuts),'traceLengths':dict(Counter(paths)),'charts':charts,'chartCount':len(charts),'sourceGeometryUnchanged':True,'sourceUvUnchanged':True}

def run(source,out):
 raw=source.read_bytes();require(28<=len(raw)<=16*1024*1024,'GLB byte budget');require(struct.unpack_from('<3I',raw)==(0x46546c67,2,len(raw)),'GLB header')
 n,kind=struct.unpack_from('<2I',raw,12);require(kind==0x4e4f534a and n<=2*1024*1024,'GLB JSON');doc=json.loads(raw[20:20+n]);length,kind=struct.unpack_from('<2I',raw,20+n);require(kind==0x004e4942 and len(raw)==28+n+length,'one BIN');binary=raw[28+n:]
 def array(index,dimension,dtype):
  a=doc['accessors'][index];view=doc['bufferViews'][a['bufferView']];require(a['componentType']==(5126 if dtype=='<f4' else 5125) and a['type']=={1:'SCALAR',2:'VEC2',3:'VEC3'}[dimension],'bounded packed source accessor');require('byteStride' not in view and 'sparse' not in a,'packed non-sparse accessor required')
  start=view.get('byteOffset',0)+a.get('byteOffset',0);require(start>=0 and start+a['count']*dimension*4<=len(binary),'accessor byte bounds');return np.frombuffer(binary,dtype,count=a['count']*dimension,offset=start).reshape(-1,dimension)
 result=[];datasets=[];offset=0
 for mesh in doc['meshes']:
  require(len(mesh['primitives'])==1,'diagnostic one primitive per mesh');p=mesh['primitives'][0];require(p.get('mode',4)==4,'triangles required');xyz=array(p['attributes']['POSITION'],3,'<f4');uv=array(p['attributes']['TEXCOORD_0'],2,'<f4');ix=array(p['indices'],1,'<u4').reshape(-1,3)
  require(len(xyz)==len(uv) and len(xyz)<=100000 and len(ix)<=200000 and np.all(np.isfinite(xyz)) and np.all(np.isfinite(uv)) and int(ix.max())<len(xyz),'source domain bounds')
  chart,params,report=discover(xyz,ix);datasets.append((chart+offset,params,ix));offset+=report['chartCount'];result.append(report)
 out.mkdir(parents=True,exist_ok=False)
 for c,(charts,parameters,ix) in enumerate(datasets):np.savez_compressed(out/f'component-{c}.npz',chart=charts,parameter=parameters,glbSlot=ix)
 report={'status':'AUTOMATIC_GLB_QUAD_TOPOLOGY_CHART_ATTRIBUTION_NOT_NATIVE','sourceSha256':hashlib.sha256(raw).hexdigest(),'components':result,'totalCharts':offset,'renderingSlotsMerged':False,'sourceXYZOrUvEdited':False,'sourceTriangleOrderChanged':False,'hostExecuted':False,'remaining':['independent source topology/parameter recheck','fit residual and capacity','production Java port','native visibility and final menu admission']};(out/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();run(a.source,a.out)
