#!/usr/bin/env python3
"""Independent original-world ray checks for source-depth/material reference pixels.

This does not validate a native image or prove continuous-domain visibility.
"""
import argparse,hashlib,json,struct,io,math
from pathlib import Path
import numpy as np
from PIL import Image

def verify(source,reference):
 raw=source.read_bytes();report=json.loads((reference/'manifest.json').read_text());assert hashlib.sha256(raw).hexdigest()==report['sourceSha256']
 n=struct.unpack_from('<I',raw,12)[0];doc=json.loads(raw[20:20+n]);binary=raw[28+n:]
 def array(i,dim,dtype):
  a=doc['accessors'][i];b=doc['bufferViews'][a['bufferView']]
  return np.frombuffer(binary,dtype,count=a['count']*dim,offset=b.get('byteOffset',0)+a.get('byteOffset',0)).reshape(-1,dim)
 xyz=[];uvs=[];materials=[];images=[]
 for component,m in enumerate(doc['meshes']):
  p=m['primitives'][0];ix=array(p['indices'],1,'<u4').reshape(-1,3);xyz.append(array(p['attributes']['POSITION'],3,'<f4')[ix].astype(float));uvs.append(array(p['attributes']['TEXCOORD_0'],2,'<f4')[ix].astype(float));materials.extend([component]*len(ix))
  v=doc['bufferViews'][doc['images'][component]['bufferView']];images.append(np.array(Image.open(io.BytesIO(binary[v['byteOffset']:v['byteOffset']+v['byteLength']])).convert('RGBA')).astype(float))
 stem=xyz[0];triangles=np.concatenate(xyz);texture=np.concatenate(uvs);components=np.array(materials);a=triangles[:,0];e1=triangles[:,1]-a;e2=triangles[:,2]-a
 pivot=np.array([0,130.6125549674034,0]);scale=2.954462184876577;checks=[]
 for index,frame in enumerate(report['frames']):
  y,p=np.deg2rad(frame['pose']);cy,sy,cp,sp=math.cos(y),math.sin(y),math.cos(p),math.sin(p)
  rotation=np.array([[cy,sy*sp,sy*cp],[0,cp,-sp],[-sy,cy*sp,cy*cp]]);inverse=rotation.T;direction=inverse@np.array([0.,0.,1.])
  h=np.cross(direction,e2);det=np.einsum('ij,ij->i',e1,h);inv=np.divide(1.,det,out=np.zeros_like(det),where=det>1e-12)
  centers=(stem[::max(1,len(stem)//16)].mean(axis=1)-pivot)@rotation.T;pixels=centers[:,:2]*[scale,-scale]+512
  coords={(x,y) for x in range(128,897,128) for y in range(128,897,128)}
  coords.update((int(math.floor(x)),int(math.floor(y))) for x,y in pixels if 0<=x<1024 and 0<=y<1024)
  rgba=np.array(Image.open(reference/frame['png']).convert('RGBA'));depth=np.fromfile(reference/f'frame-{index}.depth',dtype='<f8').reshape(1024,1024)
  for x,y in sorted(coords):
   origin=pivot+inverse@np.array([(x+.5-512)/scale,-(y+.5-512)/scale,-1000]);s=origin-a;u=inv*np.einsum('ij,ij->i',s,h);q=np.cross(s,e1);v=inv*(q@direction);t=inv*np.einsum('ij,ij->i',e2,q)
   valid=(det>1e-12)&(u>=-1e-10)&(v>=-1e-10)&(u+v<=1+1e-10)&(t>=0)
   if not valid.any():
    assert rgba[y,x,3]==0 and math.isinf(depth[y,x]);checks.append({'pose':frame['pose'],'pixel':[x,y],'sourceHits':0});continue
   nearest=int(np.argmin(np.where(valid,t,np.inf)));z=t[nearest]-1000;assert abs(z-depth[y,x])<1e-6
   tex=(1-u[nearest]-v[nearest])*texture[nearest,0]+u[nearest]*texture[nearest,1]+v[nearest]*texture[nearest,2]
   sx,sy=np.clip(tex*[256,128]-.5,[0,0],[255,127]);xx,yy=int(math.floor(sx)),int(math.floor(sy));fx,fy=sx-xx,sy-yy;image=images[components[nearest]]
   color=(1-fy)*((1-fx)*image[yy,xx]+fx*image[yy,min(xx+1,255)])+fy*((1-fx)*image[min(yy+1,127),xx]+fx*image[min(yy+1,127),min(xx+1,255)])
   error=float(np.abs(rgba[y,x].astype(float)-np.floor(color+.5)).max());assert error<=1
   checks.append({'pose':frame['pose'],'pixel':[x,y],'sourceHits':int(valid.sum()),'nearestOriginalTriangle':nearest,'component':int(components[nearest]),'depthError':float(abs(z-depth[y,x])),'colorChannelError':error})
 result={'status':'SOURCE_RAY_DEPTH_UV_COLOR_CHECK_PASS_NOT_NATIVE','hostExecuted':False,'sourceSha256':report['sourceSha256'],'rayChecks':len(checks),'coveredSourceComponents':sorted({c['component'] for c in checks if 'component' in c}),'checks':checks,'continuousDomainProved':False}
 assert result['coveredSourceComponents']==[0,1]
 (reference/'ray-verification.json').write_text(json.dumps(result,indent=2)+'\n');print('PASS',len(checks),'independent original-world depth/UV/color/background rays; native acceptance not evaluated')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--reference',type=Path,required=True);a=p.parse_args();verify(a.source,a.reference)
