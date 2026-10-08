#!/usr/bin/env python3
"""Freeze an original-source depth/UV/material reference, never a native render."""
import argparse,hashlib,json,struct,io,subprocess
from pathlib import Path
import numpy as np
from PIL import Image
SHA='3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999'
def run(source,out,renderer):
 raw=source.read_bytes();assert hashlib.sha256(raw).hexdigest()==SHA
 n=struct.unpack_from('<I',raw,12)[0];doc=json.loads(raw[20:20+n]);binary=raw[28+n:]
 def array(i,dim,dtype):
  a=doc['accessors'][i];v=doc['bufferViews'][a['bufferView']]
  return np.frombuffer(binary,dtype,count=a['count']*dim,offset=v.get('byteOffset',0)+a.get('byteOffset',0)).reshape(-1,dim)
 out.mkdir(parents=True,exist_ok=False);chunks=[];textures=[]
 for component,mesh in enumerate(doc['meshes']):
  p=mesh['primitives'][0];assert p['material']==component
  xyz=array(p['attributes']['POSITION'],3,'<f4');uv=array(p['attributes']['TEXCOORD_0'],2,'<f4');ix=array(p['indices'],1,'<u4').reshape(-1,3)
  fields=np.concatenate((xyz[ix],uv[ix]),axis=2).reshape(-1,15)
  packed=np.empty(len(ix),dtype=np.dtype([('corners','<f4',(15,)),('material','<u4')],align=False));packed['corners']=fields;packed['material']=component
  assert packed.dtype.itemsize==64;chunks.append(packed.tobytes())
  view=doc['bufferViews'][doc['images'][component]['bufferView']];image=Image.open(io.BytesIO(binary[view['byteOffset']:view['byteOffset']+view['byteLength']])).convert('RGBA');assert image.size==(256,128);textures.append(image.tobytes())
 data=b'ASRC0001'+struct.pack('<I',150784)+b''.join(chunks+textures);(out/'source.bin').write_bytes(data)
 subprocess.run([str(renderer.resolve()),str(out/'source.bin'),str(out/'frame-')],check=True)
 poses=[(y,p) for y in (-30,0,30) for p in (-30,0,30)]+[(-13.125,9.375),(16.875,-24.375)];frames=[]
 for i,pose in enumerate(poses):
  rgba=np.fromfile(out/f'frame-{i}.rgba',dtype='uint8').reshape(1024,1024,4);Image.fromarray(rgba).save(out/f'frame-{i}.png')
  frames.append({'pose':pose,'png':f'frame-{i}.png','visiblePixelCenters':int((rgba[:,:,3]>0).sum())})
 report={'status':'ORIGINAL_SOURCE_DEPTH_UV_MATERIAL_REFERENCE_NOT_NATIVE','hostExecuted':False,'sourceSha256':SHA,'inputSha256':hashlib.sha256(data).hexdigest(),'rendererSha256':hashlib.sha256(renderer.read_bytes()).hexdigest(),'projection':'1024x1024 orthographic RyRx around source pivot; nearest minimum Z; original front winding; bilinear clamp source UV','canvasCenter':[512,512],'scale':2.954462184876577,'pivot':[0,130.6125549674034,0],'sourceTriangles':150784,'frames':frames,'antialiasing':'pixel center reference, native edge coverage must be assessed separately','remaining':'Independent source-space ray recheck and comparison with actual native captures'}
 (out/'manifest.json').write_text(json.dumps(report,indent=2)+'\n')
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--out',type=Path,required=True);p.add_argument('--renderer',type=Path,required=True);a=p.parse_args();run(a.source,a.out,a.renderer)
