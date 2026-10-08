#!/usr/bin/env python3
"""Independent source-depth comparison of complete source-face painter candidates.
Never native acceptance; every pixel center and original triangle is included.
"""
import argparse,hashlib,json,struct,subprocess,io,shutil
from pathlib import Path
import numpy as np
from PIL import Image
SOURCE_SHA='4d0688298a366bfd5501918a7afac5dfd4f6aa35445a4f73077435045ba02ee7'
POSES=[(0,0),(-180,-180),(180,180),(0,-180),(180,0),(90,90),(-47,35),(103,-127),(-112.5,37.5),(75,-150),(179.5,-179.5),(45,45)]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def run(a):
 raw=a.source.read_bytes()
 if hashlib.sha256(raw).hexdigest()!=SOURCE_SHA:raise ValueError('authorized source identity')
 n=struct.unpack_from('<I',raw,12)[0];doc=json.loads(raw[20:20+n]);binary=raw[28+n:]
 def array(i,dim,dtype):
  ac=doc['accessors'][i];v=doc['bufferViews'][ac['bufferView']]
  if 'byteStride' in v:raise ValueError('exact contiguous source required')
  return np.frombuffer(binary,dtype,count=ac['count']*dim,offset=v.get('byteOffset',0)+ac.get('byteOffset',0)).reshape(-1,dim)
 primitive=doc['meshes'][0]['primitives'][0];xyz=array(primitive['attributes']['POSITION'],3,'<f4');uv=array(primitive['attributes']['TEXCOORD_0'],2,'<f4');ix=array(primitive['indices'],1,'<u4').reshape(-1,3)
 if xyz.shape!=(36530,3) or len(ix)!=70576 or primitive['material']!=0:raise ValueError('source inventory')
 out=a.out.absolute();out.mkdir(exist_ok=False,parents=True)
 packed=np.empty(len(ix),dtype=np.dtype([('corners','<f4',(3,5)),('material','<u4')]));packed['corners']=np.concatenate((xyz[ix],uv[ix]),axis=2);packed['material']=0
 view=doc['bufferViews'][doc['images'][0]['bufferView']];image=Image.open(io.BytesIO(binary[view['byteOffset']:view['byteOffset']+view['byteLength']])).convert('RGBA')
 if image.size!=(1024,1024) or doc['materials'][0]['pbrMetallicRoughness']['baseColorFactor']!=[1,1,1,1]:raise ValueError('full source texture and unit factor required')
 data=b'ASRC0001'+struct.pack('<I',len(ix))+packed.tobytes()+image.tobytes();(out/'source.bin').write_bytes(data)
 camera=list(map(float,a.camera));scale,px,py,pz=camera
 cpp=Path(__file__).with_name('apple-source-raster.cpp').read_text().replace('width=256,height=128','width=1024,height=1024').replace('count!=150784','count!=70576').replace('std::array<std::vector<uint8_t>,2> textures','std::array<std::vector<uint8_t>,1> textures').replace('tri.material>1','tri.material>0')
 start=cpp.index('    std::vector<std::pair<double,double>> poses;');end=cpp.index('    for(size_t pose=',start)
 cpp=cpp[:start]+'    std::vector<std::pair<double,double>> poses={'+','.join('{'+str(y)+','+str(p)+'}' for y,p in POSES)+'};\n'+cpp[end:]
 old='double y=a.y-130.6125549674034,yp=cp*y-sp*a.z,zp=sp*y+cp*a.z;s[i]={512+2.954462184876577*(cy*a.x+sy*zp),512-2.954462184876577*yp,-sy*a.x+cy*zp,a.u,a.v};'
 new=f'double x=a.x-({px}),y=a.y-({py}),z=a.z-({pz}),yp=cp*y-sp*z,zp=sp*y+cp*z;s[i]={{512+({scale})*(cy*x+sy*zp+({px})),512-({scale})*(yp+({py})),-sy*x+cy*zp+({pz}),a.u,a.v}};'
 if cpp.count(old)!=1:raise ValueError('oracle template projection drift')
 cpp=cpp.replace(old,new)
 def build(name,text):
  (out/(name+'.cpp')).write_text(text);subprocess.run(['c++','-O2','-std=c++17',str(out/(name+'.cpp')),'-o',str(out/name)],check=True)
 if a.reference:
  frozen=a.reference.absolute();inventory=json.loads((frozen/'artifact-manifest.json').read_text());previous=json.loads((frozen/'report.json').read_text())
  names=['source.bin','reference.cpp','reference','painter.cpp','painter']+[f'reference-{i}.{suffix}' for i in range(len(POSES)) for suffix in ('rgba','depth')]
  if previous['sourceSha256']!=SOURCE_SHA or previous['camera']!=camera or sha(frozen/'source.bin')!=sha(out/'source.bin') or (frozen/'reference.cpp').read_text()!=cpp:raise ValueError('reused oracle domain identity')
  for name in names:
   if sha(frozen/name)!=inventory[name]:raise ValueError('reused oracle artifact '+name)
   if name!='source.bin':shutil.copy2(frozen/name,out/name)
  (out/'reference.log').write_text('REUSED_FROZEN_IDENTICAL_SOURCE_ORACLE '+str(frozen)+'\n')
 else:
  build('reference',cpp);subprocess.run([str(out/'reference'),str(out/'source.bin'),str(out/'reference-')],check=True,stdout=(out/'reference.log').open('w'))
 painter=cpp.replace('if(argc!=3)','if(argc!=4)').replace('for(const auto& tri:triangles) {\n            std::array<Screen,3> s;', '''std::ifstream orderFile(std::string(argv[3])+"/"+std::to_string(pose)+".bin",std::ios::binary);
        std::vector<uint32_t> order(count);orderFile.read(reinterpret_cast<char*>(order.data()),count*4);
        if(!orderFile || orderFile.peek()!=std::char_traits<char>::eof())throw std::runtime_error("order length");
        auto sorted=order;std::sort(sorted.begin(),sorted.end());for(uint32_t i=0;i<count;i++)if(sorted[i]!=i)throw std::runtime_error("order permutation");
        for(uint32_t id:order){const auto& tri=triangles[id];
            std::array<Screen,3> s;''').replace('if(z<depth[offset])','if(true)').replace('SOURCE_REFERENCE','SOURCE_PATCH_PAINTER')
 if 'orderFile' not in painter:raise ValueError('oracle iteration drift')
 if a.reference:
  if (out/'painter.cpp').read_text()!=painter:raise ValueError('reused painter template identity')
 else:build('painter',painter)
 centroids=xyz[ix].astype(float).mean(axis=1);rows=[]
 for count in (64,128,256,512):
  path=a.groups/f'groups-{count}.bin';groups=np.fromfile(path,dtype='>i4')
  if len(groups)!=len(ix) or set(groups)!=set(range(count)):raise ValueError('complete source face partition')
  centers=np.array([centroids[groups==i].mean(axis=0) for i in range(count)]);folder=out/str(count);folder.mkdir();orders=folder/'orders';orders.mkdir()
  for pose,(yaw,pitch) in enumerate(POSES):
   y,p=np.deg2rad([yaw,pitch]);q=centers-np.array([px,py,pz]);depth=-np.sin(y)*q[:,0]+np.cos(y)*(np.sin(p)*q[:,1]+np.cos(p)*q[:,2]);order=np.argsort(-depth,kind='stable');ranks=np.empty(count,dtype=int);ranks[order]=np.arange(count)
   np.lexsort((np.arange(len(ix)),ranks[groups])).astype('<u4').tofile(orders/f'{pose}.bin')
  subprocess.run([str(out/'painter'),str(out/'source.bin'),str(folder/'frame-'),str(orders)],check=True,stdout=(folder/'run.log').open('w'))
  for pose,angles in enumerate(POSES):
   actual=np.fromfile(folder/f'frame-{pose}.rgba',dtype='u1').reshape(1024,1024,4);expected=np.fromfile(out/f'reference-{pose}.rgba',dtype='u1').reshape(1024,1024,4)
   z=np.fromfile(folder/f'frame-{pose}.depth',dtype='<f8');near=np.fromfile(out/f'reference-{pose}.depth',dtype='<f8');visible=np.isfinite(near)
   if not np.array_equal(np.isfinite(z),visible):raise ValueError('source coverage changed')
   gap=z[visible]-near[visible];delta=np.abs(actual.astype(int)-expected.astype(int));bad=gap>1e-5
   row=dict(meshes=count,poseYawPitch=angles,visiblePixels=int(visible.sum()),wrongDepthPixels=int(bad.sum()),wrongDepthFraction=float(bad.mean()),maximumDepthGap=float(gap.max()),colorErrorOver16Pixels=int((delta[visible.reshape(1024,1024),:3].max(axis=1)>16).sum()),meanRGBAError=delta.mean(axis=(0,1)).tolist());rows.append(row);print(json.dumps(row),flush=True)
   if pose in (0,6,7,8):Image.fromarray(actual).save(folder/f'frame-{pose}.png');Image.fromarray(expected).save(out/f'reference-{pose}.png')
 report=dict(status='SOURCE_PATCH_VISIBILITY_MEASUREMENT_NOT_NATIVE',hostExecuted=False,visibilityAccepted=False,sourceSha256=SOURCE_SHA,sourceTriangles=len(ix),sourceSlots=len(xyz),camera=camera,allOriginalFaces=True,boundaryMask=False,orderScope='exact centroid depth at requested pose; excludes parameter interpolation',groups={str(c):sha(a.groups/f'groups-{c}.bin') for c in (64,128,256,512)},rows=rows)
 (out/'report.json').write_text(json.dumps(report,indent=2)+'\n');(out/'artifact-manifest.json').write_text(json.dumps({str(p.relative_to(out)):sha(p) for p in out.rglob('*') if p.is_file()},indent=2)+'\n')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--reference',type=Path);p.add_argument('--source',type=Path,required=True);p.add_argument('--groups',type=Path,required=True);p.add_argument('--camera',nargs=4,required=True);p.add_argument('--out',type=Path,required=True);run(p.parse_args())
