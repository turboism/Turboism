#!/usr/bin/env python3
"""Attach deterministic validation textures to the authorized original apple GLB.

The archive has no OBJ materials. These are newly authored source textures, not
recovered originals or Cubism colors. Original accessors and buffer bytes remain
unchanged; no geometry simplification or UV remapping is performed.
"""
import argparse,hashlib,json,math,struct
from pathlib import Path
import numpy as np
from PIL import Image
SOURCE_SHA='54331e29af9a3ca07f11017712ce9156208a8ae8538b0a80b78e8c5eb8a894e0'

def textures():
    # Periodic UV patterns keep the u=0/1 seam coherent. No baked rotation lighting.
    y,x=np.mgrid[0:128,0:256];u=x/255;v=y/127
    mottling=np.sin(2*math.pi*(5*u+2*v))*np.sin(2*math.pi*(3*u-v))
    streak=np.sin(2*math.pi*17*u+0.8*np.sin(2*math.pi*3*v))
    speckles=(np.sin(2*math.pi*(73*u+47*v))*np.sin(2*math.pi*(59*u-31*v))>.985)
    skin=np.stack((172+27*mottling+10*streak+45*speckles,29+8*mottling+6*streak+38*speckles,24+5*mottling+4*streak+25*speckles),axis=-1)
    bark=np.sin(2*math.pi*23*u+0.6*np.sin(2*math.pi*2*v))
    stem=np.stack((93+17*bark,54+12*bark,27+6*bark),axis=-1)
    return [np.clip(stem,0,255).astype('uint8'),np.clip(skin,0,255).astype('uint8')]

def run(source,out):
    raw=source.read_bytes()
    if hashlib.sha256(raw).hexdigest()!=SOURCE_SHA:raise ValueError('exact authorized original GLB required')
    if out.exists():raise ValueError('new output directory required')
    magic,version,total=struct.unpack_from('<3I',raw);assert magic==0x46546c67 and version==2 and total==len(raw)
    size,kind=struct.unpack_from('<2I',raw,12);assert kind==0x4e4f534a
    doc=json.loads(raw[20:20+size]);offset=20+size
    count,kind=struct.unpack_from('<2I',raw,offset);assert kind==0x004e4942 and offset+8+count==len(raw)
    original=raw[offset+8:offset+8+count]
    assert len(doc['meshes'])==2 and 'materials' not in doc and 'images' not in doc
    original_accessors=json.loads(json.dumps(doc['accessors']));original_views=json.loads(json.dumps(doc['bufferViews']))
    binary=bytearray(original);out.mkdir(parents=True,exist_ok=False)
    images=[]
    for i,(name,pixels) in enumerate(zip(('stem','skin'),textures())):
        file=out/(name+'.png');Image.fromarray(pixels).save(file)
        png=file.read_bytes()
        while len(binary)%4:binary.append(0)
        begin=len(binary);binary.extend(png)
        doc['bufferViews'].append({'buffer':0,'byteOffset':begin,'byteLength':len(png)})
        images.append({'name':'Authored apple '+name,'bufferView':len(doc['bufferViews'])-1,'mimeType':'image/png'})
    doc['images']=images;doc['samplers']=[{'magFilter':9729,'minFilter':9729,'wrapS':33071,'wrapT':33071}]
    doc['textures']=[{'sampler':0,'source':i} for i in range(2)]
    doc['materials']=[{'name':'Authored apple '+name,'pbrMetallicRoughness':{'baseColorFactor':[1,1,1,1],'baseColorTexture':{'index':i,'texCoord':0},'metallicFactor':0,'roughnessFactor':.75},'doubleSided':False} for i,name in enumerate(('stem','skin'))]
    for i,mesh in enumerate(doc['meshes']):
        assert len(mesh['primitives'])==1;mesh['primitives'][0]['material']=i
    doc['buffers'][0]['byteLength']=len(binary)
    doc['asset']['generator']='Turboism original apple geometry with authored validation source textures'
    assert doc['accessors']==original_accessors and doc['bufferViews'][:len(original_views)]==original_views
    assert binary[:len(original)]==original
    text=json.dumps(doc,separators=(',',':')).encode();text+=b' '*((-len(text))%4);binary+=b'\0'*((-len(binary))%4)
    packed=struct.pack('<3I',0x46546c67,2,12+8+len(text)+8+len(binary))+struct.pack('<2I',len(text),0x4e4f534a)+text+struct.pack('<2I',len(binary),0x004e4942)+binary
    assert len(packed)<=16*1024*1024
    (out/'apple-textured.glb').write_bytes(packed)
    result={'status':'SOURCE_TEXTURED_NOT_IMPORTED','sourceSha256':SOURCE_SHA,'glbSha256':hashlib.sha256(packed).hexdigest(),'glbBytes':len(packed),'sourceBufferPrefixExact':True,'originalAccessorsExact':True,'originalUvUnchanged':True,'simplified':False,'originalArchiveHadMaterials':False,'materialOrigin':'NEW_DETERMINISTIC_SOURCE_TEXTURES','colorBinding':'GLB_BASE_COLOR_TEXTURE_NOT_CUBISM_COLOR','sourceMaterials':['stem','skin'],'hostExecuted':False}
    (out/'manifest.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();run(a.source,a.out)
