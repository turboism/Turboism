#!/usr/bin/env python3
"""Weighted front-layer graph experiment on frozen source faces; no native acceptance."""
import argparse,hashlib,json,subprocess
from pathlib import Path
import numpy as np
from PIL import Image

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def run(a):
 reference=a.reference.absolute();manifest=json.loads((reference/'artifact-manifest.json').read_text())
 for name in ('source.bin','reference.cpp','painter'):
  if sha(reference/name)!=manifest[name]:raise ValueError('frozen reference identity '+name)
 original=json.loads((reference/'report.json').read_text());poses=[r['poseYawPitch'] for r in original['rows'] if r['meshes']==64]
 out=a.out.absolute();out.mkdir(exist_ok=False,parents=True)
 cpp=(reference/'reference.cpp').read_text();cpp=cpp.replace('if(argc!=3)','if(argc!=5)')
 insert='''
    int groupCount=std::stoi(argv[4]);if(groupCount<1 || groupCount>1001)throw std::runtime_error("group count");
    std::ifstream gf(argv[3],std::ios::binary);std::vector<uint32_t> groups(count);
    for(auto& g:groups){uint8_t b[4];gf.read(reinterpret_cast<char*>(b),4);g=(uint32_t(b[0])<<24)|(uint32_t(b[1])<<16)|(uint32_t(b[2])<<8)|b[3];if(g>=uint32_t(groupCount))throw std::runtime_error("group id");}
    if(!gf || gf.peek()!=std::char_traits<char>::eof())throw std::runtime_error("group file length");
'''
 cpp=cpp.replace('    std::vector<std::pair<double,double>> poses=',insert+'    std::vector<std::pair<double,double>> poses=',1)
 cpp=cpp.replace('std::vector<double> depth(size*size,','std::vector<int> owner(size*size,-1);std::vector<uint8_t> selfBad(size*size,0);std::vector<uint64_t> weights(size_t(groupCount)*groupCount,0);\n        std::vector<double> depth(size*size,',1)
 cpp=cpp.replace('if(z<depth[offset]){depth[offset]=z;','if(z<depth[offset]){owner[offset]=int(&tri-triangles.data());depth[offset]=z;',1)
 start=cpp.index('        for(const auto& tri:triangles) {\n            std::array<Screen,3> s;');end=cpp.index('        std::string prefix=',start)
 loop=cpp[start:end]
 begin=loop.index('                if(z<depth[offset])');stop=loop.index('\n            }',begin)
 loop=loop[:begin]+'''                if(owner[offset]>=0 && z-depth[offset]>1e-5){
                    uint32_t back=groups[&tri-triangles.data()],front=groups[owner[offset]];
                    if(back!=front)weights[size_t(back)*groupCount+front]++;
                    else if(int(&tri-triangles.data())>owner[offset])selfBad[offset]=1;
                }'''+loop[stop:]
 graphwrite='''
        std::string gp=std::string(argv[2])+std::to_string(pose);
        std::ofstream graph(gp+".graph",std::ios::binary);graph.write(reinterpret_cast<char*>(weights.data()),weights.size()*sizeof(uint64_t));
        size_t selfCount=std::count(selfBad.begin(),selfBad.end(),uint8_t(1));
        std::cout<<"SELF_WRONG pose="<<pose<<" pixels="<<selfCount<<"\\n";
'''
 cpp=cpp[:end]+loop+graphwrite+cpp[end:]
 (out/'front-graph.cpp').write_text(cpp);subprocess.run(['c++','-O2','-std=c++17',str(out/'front-graph.cpp'),'-o',str(out/'front-graph')],check=True)
 def order(weights):
  w=weights.astype(float);n=len(w);active=np.ones(n,dtype=bool);incoming=w.sum(axis=0);score=w.sum(axis=1)-incoming;result=[]
  for _ in range(n):
   roots=np.flatnonzero(active & (incoming==0));candidates=roots if len(roots) else np.flatnonzero(active)
   node=int(candidates[np.argmax(score[candidates])]);result.append(node);active[node]=False;incoming-=w[node,:];score+=w[node,:]-w[:,node]
  # Relocate a whole group only when it strictly reduces conflicting front-layer weights.
  for _ in range(8):
   changed=False
   for node in list(result):
    old=result.index(node);others=[x for x in result if x!=node];cost=float(w[others,node].sum());costs=[cost]
    for peer in others:cost+=w[node,peer]-w[peer,node];costs.append(cost)
    at=int(np.argmin(costs))
    if costs[at]<costs[old]:result=others[:at]+[node]+others[at:];changed=True
   if not changed:break
  return np.array(result,dtype=int)
 rows=[]
 for count in (64,128,256,512):
  groupFile=a.groups/f'groups-{count}.bin'
  if sha(groupFile)!=original['groups'][str(count)]:raise ValueError('partition identity')
  groups=np.fromfile(groupFile,dtype='>i4');folder=out/str(count);folder.mkdir();orders=folder/'orders';orders.mkdir()
  with (folder/'graph.log').open('w') as log:subprocess.run([str(out/'front-graph'),str(reference/'source.bin'),str(folder/'graph-'),str(groupFile),str(count)],check=True,stdout=log)
  for pose in range(len(poses)):
   weights=np.fromfile(folder/f'graph-{pose}.graph',dtype='<u8').reshape(count,count);permutation=order(weights);ranks=np.empty(count,dtype=int);ranks[permutation]=np.arange(count)
   np.lexsort((np.arange(len(groups)),ranks[groups])).astype('<u4').tofile(orders/f'{pose}.bin')
   np.asarray(permutation,dtype='<u4').tofile(folder/f'group-order-{pose}.bin')
  with (folder/'painter.log').open('w') as log:subprocess.run([str(reference/'painter'),str(reference/'source.bin'),str(folder/'frame-'),str(orders)],check=True,stdout=log)
  selfRows={int(line.split('pose=')[1].split()[0]):int(line.split('pixels=')[1]) for line in (folder/'graph.log').read_text().splitlines() if line.startswith('SELF_WRONG')}
  for pose,angles in enumerate(poses):
   actual=np.fromfile(folder/f'frame-{pose}.rgba',dtype='u1').reshape(1024,1024,4);expected=np.fromfile(reference/f'reference-{pose}.rgba',dtype='u1').reshape(1024,1024,4)
   z=np.fromfile(folder/f'frame-{pose}.depth',dtype='<f8');near=np.fromfile(reference/f'reference-{pose}.depth',dtype='<f8');visible=np.isfinite(near)
   if not np.array_equal(np.isfinite(z),visible):raise ValueError('source coverage changed')
   gap=z[visible]-near[visible];delta=np.abs(actual.astype(int)-expected.astype(int));bad=gap>1e-5
   row=dict(meshes=count,poseYawPitch=angles,visiblePixels=int(visible.sum()),selfWrongPixels=selfRows[pose],wrongDepthPixels=int(bad.sum()),wrongDepthFraction=float(bad.mean()),maximumDepthGap=float(gap.max()),colorErrorOver16Pixels=int((delta[visible.reshape(1024,1024),:3].max(axis=1)>16).sum()),meanRGBAError=delta.mean(axis=(0,1)).tolist());rows.append(row);print(json.dumps(row),flush=True)
   if pose in (0,6,7,8):Image.fromarray(actual).save(folder/f'frame-{pose}.png')
 report=dict(status='SOURCE_FRONT_LAYER_GRAPH_MEASUREMENT_NOT_NATIVE',visibilityAccepted=False,hostExecuted=False,sourceSha256=original['sourceSha256'],allOriginalFaces=True,boundaryMask=False,referenceManifestSha256=sha(reference/'artifact-manifest.json'),orderScope='source raster front-layer graph at requested pose; excludes key interpolation and continuous-domain proof',rows=rows)
 (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');(out/'artifact-manifest.json').write_text(json.dumps({str(p.relative_to(out)):sha(p) for p in out.rglob('*') if p.is_file()},indent=2)+'\n')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--reference',type=Path,required=True);p.add_argument('--groups',type=Path,required=True);p.add_argument('--out',type=Path,required=True);run(p.parse_args())
