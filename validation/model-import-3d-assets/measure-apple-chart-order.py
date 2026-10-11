#!/usr/bin/env python3
"""Source-only chart painter experiment; retains all triangles, no native acceptance."""
import argparse,hashlib,json,struct,subprocess
from pathlib import Path
import numpy as np
from PIL import Image
POSES=[(y,p) for y in (-30,0,30) for p in (-30,0,30)]+[(-13.125,9.375),(16.875,-24.375)]
KEYS=np.linspace(-30,30,17)
def digest(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def run(reference,attributed,out):
 manifest=json.loads((reference/'artifact-manifest.json').read_text())
 for name in ['source.bin']+[f'frame-{i}.{suffix}' for i in range(11) for suffix in ('rgba','depth')]:
  if digest(reference/name)!=manifest[name]:raise ValueError('reference identity '+name)
 raw=(reference/'source.bin').read_bytes()
 if raw[:8]!=b'ASRC0001' or struct.unpack_from('<I',raw,8)[0]!=150784:raise ValueError('source inventory')
 tri=np.frombuffer(raw,dtype=np.dtype([('v','<f4',(3,5)),('material','<u4')]),count=150784,offset=12)
 attr=attributed.read_bytes()
 if hashlib.sha256(attr).hexdigest()!='e104cdf12c789927d1b23bd36fe851c612c398e21af005c22d03e87ec4cb47bb':raise ValueError('attribution identity')
 if attr[:8]!=b'CPACK001' or struct.unpack_from('>i',attr,8)[0]!=150784:raise ValueError('attribution inventory')
 chart=np.frombuffer(attr,dtype=np.dtype([('chart','>i4'),('parameter','>f8',(3,2))]),count=150784,offset=12)['chart'].astype(int)
 count=int(chart.max())+1
 if count!=98 or len(set(chart))!=98:raise ValueError('complete chart inventory')
 centroid=tri['v'][:,:,:3].astype(float).mean(axis=1)
 centers=np.array([centroid[chart==i].mean(axis=0) for i in range(count)])
 def depths(y,p):
  y,p=np.deg2rad([y,p]);return -np.sin(y)*centers[:,0]+np.cos(y)*(np.sin(p)*(centers[:,1]-130.6125549674034)+np.cos(p)*centers[:,2])
 def ranks(y,p):
  order=np.argsort(-depths(y,p),kind='stable');rank=np.empty(count,dtype=float);rank[order]=np.arange(count);return rank
 bank=np.array([[ranks(y,p) for p in KEYS] for y in KEYS])
 def interpolated(y,p):
  yi=min(15,int(np.searchsorted(KEYS,y,side='right')-1));pi=min(15,int(np.searchsorted(KEYS,p,side='right')-1));fy=(y-KEYS[yi])/3.75;fp=(p-KEYS[pi])/3.75
  # Integer keyform orders spread across the SDK's 0..1000 domain.
  values=8*bank+100
  return np.rint((1-fy)*((1-fp)*values[yi,pi]+fp*values[yi,pi+1])+fy*((1-fp)*values[yi+1,pi]+fp*values[yi+1,pi+1]))
 out.mkdir(parents=True,exist_ok=False);(out/'orders').mkdir();rows=[]
 for i,(y,p) in enumerate(POSES):
  score=interpolated(y,p);order=np.lexsort((-centroid[:,2],score[chart])).astype('<u4')
  if not np.array_equal(np.sort(order),np.arange(150784)):raise ValueError('triangle permutation')
  order.tofile(out/'orders'/f'{i}.bin')
 source=Path(__file__).with_name('apple-source-raster.cpp').read_text()
 source=source.replace('if(argc!=3)','if(argc!=4)').replace('source input and output prefix required','source input, output prefix and chart orders required')
 source=source.replace('for(const auto& tri:triangles) {\n            std::array<Screen,3> s;', '''std::ifstream orderFile(std::string(argv[3])+"/"+std::to_string(pose)+".bin",std::ios::binary);
        std::vector<uint32_t> order(count);orderFile.read(reinterpret_cast<char*>(order.data()),count*4);
        if(!orderFile || orderFile.peek()!=std::char_traits<char>::eof())throw std::runtime_error("order length");
        auto sorted=order;std::sort(sorted.begin(),sorted.end());for(uint32_t i=0;i<count;i++)if(sorted[i]!=i)throw std::runtime_error("order permutation");
        for(uint32_t id:order) {const auto& tri=triangles[id];
            std::array<Screen,3> s;''')
 if source.count('if(z<depth[offset])')!=1 or 'orderFile' not in source:raise ValueError('renderer template drift')
 source=source.replace('if(z<depth[offset])','if(true)').replace('SOURCE_REFERENCE','CHART_PAINTER_DIAGNOSTIC')
 (out/'chart-painter.cpp').write_text(source)
 subprocess.run(['c++','-O2','-std=c++17',str(out/'chart-painter.cpp'),'-o',str(out/'chart-painter')],check=True)
 subprocess.run([str((out/'chart-painter').resolve()),str((reference/'source.bin').resolve()),str((out/'frame-').resolve()),str((out/'orders').resolve())],check=True)
 for i,pose in enumerate(POSES):
  actual=np.fromfile(out/f'frame-{i}.rgba',dtype='u1').reshape(1024,1024,4);expected=np.fromfile(reference/f'frame-{i}.rgba',dtype='u1').reshape(1024,1024,4)
  z=np.fromfile(out/f'frame-{i}.depth',dtype='<f8');near=np.fromfile(reference/f'frame-{i}.depth',dtype='<f8');visible=np.isfinite(near)
  if not np.array_equal(np.isfinite(z),visible):raise ValueError('changed source coverage')
  gap=z[visible]-near[visible];bad=gap>1e-5
  row={'pose':pose,'wrongDepthPixels':int(bad.sum()),'maximumDepthGap':float(gap.max()),'meanRGBAError':np.abs(actual.astype(int)-expected.astype(int)).mean(axis=(0,1)).tolist()};rows.append(row);print(json.dumps(row),flush=True)
  Image.fromarray(actual).save(out/f'frame-{i}.png')
 report={'status':'SOURCE_CHART_PAINTER_DIAGNOSTIC_NOT_NATIVE','charts':count,'triangles':150784,'keysPerAxis':17,'allOriginalTrianglesRetained':True,'boundaryMask':False,'hostExecuted':False,'visibilityAccepted':False,'referenceSha256':digest(reference/'source.bin'),'attributionSha256':digest(attributed),'rows':rows}
 (out/'report.json').write_text(json.dumps(report,indent=2)+'\n')
 (out/'artifact-manifest.json').write_text(json.dumps({str(p.relative_to(out)):digest(p) for p in out.rglob('*') if p.is_file()},indent=2)+'\n')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--reference',type=Path,required=True);p.add_argument('--attributed',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();run(a.reference,a.attributed,a.out)
