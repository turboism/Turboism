#!/usr/bin/env python3
"""Read native CAFF image entries and compare preserved Cat diffuse texels to source GLB."""
import argparse,hashlib,io,json,struct
from pathlib import Path
from PIL import Image
SOURCE_SHA='4d0688298a366bfd5501918a7afac5dfd4f6aa35445a4f73077435045ba02ee7'
def require(ok,why):
 if not ok:raise ValueError(why)
def sha(data):return hashlib.sha256(data).hexdigest()
def verify(model,source,out):
 require(not out.exists(),'new report required')
 raw=source.read_bytes();require(sha(raw)==SOURCE_SHA,'authorized source identity')
 size=struct.unpack_from('<I',raw,12)[0];doc=json.loads(raw[20:20+size]);binary=raw[28+size:]
 require(len(doc['materials'])==1 and doc['materials'][0]['pbrMetallicRoughness']['baseColorFactor']==[1,1,1,1],'source unit diffuse factor')
 texture=doc['textures'][doc['materials'][0]['pbrMetallicRoughness']['baseColorTexture']['index']]
 view=doc['bufferViews'][doc['images'][texture['source']]['bufferView']];start=view.get('byteOffset',0)
 with Image.open(io.BytesIO(binary[start:start+view['byteLength']])) as image:
  require(image.size==(1024,1024),'source diffuse size');image.load();expected=sha(image.convert('RGBA').tobytes())
 length=model.stat().st_size;require(54<length<=256*1024*1024,'bounded model file')
 rows=[];images=[]
 with model.open('rb') as f:
  header=f.read(54);require(header[:4]==b'CAFF','native archive signature');key=struct.unpack('>I',header[14:18])[0]
  def read(n):
   require(0<=n<=8*1024*1024,'bounded entry read');b=f.read(n);require(len(b)==n,'complete archive read');return b
  def number(fmt):
   value=struct.unpack(fmt,read(struct.calcsize(fmt)))[0]
   signed=key|0xffffffff00000000 if key&0x80000000 else key
   mask=key if fmt=='>I' else ((signed<<32)|signed)&0xffffffffffffffff
   return value^mask
  def byte():return read(1)[0]^(key&255)
  def string():
   size=0
   for _ in range(5):
    b=byte();size=(size<<7)|(b&127)
    if b<128:break
   else:raise ValueError('path varint overflow')
   require(size<65536,'bounded archive path');return bytes(v^(key&255) for v in read(size)).decode()
  count=number('>I');require(0<count<10000,'archive inventory count')
  for _ in range(count):
   path=string();tag=string();offset=number('>Q');size=number('>I');flag=byte();compression=byte();read(8)
   require(offset+size<=length and size>0,'entry inside complete file')
   rows.append(dict(path=path,tag=tag,offset=offset,bytes=size,flag=flag,compression=compression))
  end=f.tell();require(len({r['path'] for r in rows})==len(rows),'unique archive entries')
  ranges=sorted((r['offset'],r['offset']+r['bytes']) for r in rows)
  require(ranges[0][0]>=end and all(a[1]<=b[0] for a,b in zip(ranges,ranges[1:])),'nonoverlapping payloads outside metadata')
  for row in rows:
   if not row['path'].endswith('.png'):continue
   require(row['flag']==1 and row['compression']==16,'known native PNG encoding')
   f.seek(row['offset']);encoded=read(row['bytes']);decoded=bytes(v^(key&255) for v in encoded)
   require(decoded.startswith(b'\x89PNG\r\n\x1a\n'),'decoded PNG signature')
   with Image.open(io.BytesIO(decoded)) as image:
    require(image.width*image.height<=4*1024*1024,'bounded image pixels');image.load();pixels=sha(image.convert('RGBA').tobytes())
    images.append(dict(path=row['path'],size=list(image.size),rgbaSha256=pixels,matchesOriginalDiffuse=image.size==(1024,1024) and pixels==expected))
 # Native imageFileBuf is CImageResource's original buffer, not a preview by
 # filename convention. Archive pixel preservation alone does not prove which
 # mesh uses the resource; bank and fresh-frame audits cover that separately.
 matched=any(i['matchesOriginalDiffuse'] for i in images)
 result=dict(status='PASS_CAT_SERIALIZED_DIFFUSE_PIXEL_PRESERVATION_ONLY' if matched else 'FAIL_CAT_SERIALIZED_DIFFUSE_EXACT_RGBA',sourceSha256=SOURCE_SHA,modelSha256=sha(model.read_bytes()),sourceRGBAsha256=expected,archiveEntryCount=len(rows),images=images,fullXMLDecoded=False,freshReopenAccepted=False,shaderBindingProven=False)
 out.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({k:v for k,v in result.items() if k!='images'},indent=2))
 require(matched,'original diffuse texels missing from serialized model images')
if __name__=='__main__':
 p=argparse.ArgumentParser()
 for name in ('model','source','out'):p.add_argument('--'+name,type=Path,required=True)
 a=p.parse_args();verify(a.model,a.source,a.out)
