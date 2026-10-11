#!/usr/bin/env python3
"""Pack authored source textures for mixed-material Warp meshes without resampling.

Original GLB and fixture UVs remain unchanged. Derived shader UVs have an explicit
invertible image transform per original component; this is not native import evidence.
"""
from pathlib import Path
import argparse,hashlib,json,struct,io
import numpy as np
from PIL import Image

def run(source,fixture,out):
    raw=source.read_bytes();n=struct.unpack_from('<I',raw,12)[0];doc=json.loads(raw[20:20+n]);bin_start=28+n
    assert len(doc['images'])==len(doc['materials'])==2
    images=[]
    for entry in doc['images']:
        view=doc['bufferViews'][entry['bufferView']];offset=bin_start+view.get('byteOffset',0)
        image=np.array(Image.open(io.BytesIO(raw[offset:offset+view['byteLength']])).convert('RGBA'))
        assert image.shape==(128,256,4) and np.all(image[:,:,3]==255);images.append(image)
    out.mkdir(parents=True,exist_ok=False)
    atlas=np.empty((256,1024,4),dtype='uint8');sy=np.clip(np.arange(256)-1,0,127);sx=np.clip(np.arange(512)-1,0,255)
    for i,image in enumerate(images):atlas[:,i*512:(i+1)*512]=image[sy[:,None],sx[None,:]]
    Image.fromarray(atlas).save(out/'atlas.png')
    hashes=[]
    for i in range(2):
        with np.load(fixture/f'mesh-{i}.npz') as d:
            uv=d['textureUV'];component=d['slotIdentity'][:,0]
            assert np.all((uv>=0)&(uv<=1)) and set(np.unique(component))<=set(range(2))
            pixel=np.stack((component*512+1+uv[:,0].astype(float)*256,1+uv[:,1].astype(float)*128),axis=-1)
            mapped=np.asarray(pixel/[1024,256],dtype='<f4')
            np.savez_compressed(out/f'mesh-{i}-image-input.npz',originalUv=uv,shaderUv=mapped,imagePixels=np.asarray(pixel,dtype='<f4'),sourceComponent=component)
            assert np.max(np.abs(mapped.astype(float)*[1024,256]-pixel))<=.0001
            hashes.append(hashlib.sha256(uv.tobytes()).hexdigest())
    for i,image in enumerate(images):assert np.array_equal(atlas[1:129,i*512+1:i*512+257],image)
    report={'status':'SOURCE_IMAGE_ATLAS_PREPARED_NOT_BOUND','hostExecuted':False,'sourceGlbSha256':hashlib.sha256(raw).hexdigest(),'atlasSha256':hashlib.sha256((out/'atlas.png').read_bytes()).hexdigest(),'atlasDimensions':[1024,256],'sourceTexelsExact':True,'resampled':False,'originalUvHashes':hashes,'shaderUvTransform':'((component*512+1+256*u)/1024,(1+128*v)/256)','originalUVRetained':True,'imageRegistrationsPlanned':1,'remaining':'Native createWithSourceGeometry + identity parent attachment + model-image binding must preserve default Warp local positions and shader UV correspondence.'}
    (out/'manifest.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--source',type=Path,required=True);p.add_argument('--fixture',type=Path,required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args();run(a.source,a.fixture,a.out)
