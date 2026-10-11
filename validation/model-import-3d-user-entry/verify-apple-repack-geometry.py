#!/usr/bin/env python3
"""Compare all repacked source bytes against the accepted first native packing."""
import hashlib
import json
from pathlib import Path
import numpy as np


def require(ok,why):
    if not ok:raise ValueError('APPLE_REPACK_SOURCE: '+why)


def compare(first,second,count,delta):
    require(len(first)==len(second) and len(first)>=count*16,'complete equal geometry bytes')
    require(first[:count*8]==second[:count*8],'source XY exact')
    require(first[count*16:]==second[count*16:],'full indices exact')
    old=np.frombuffer(first[count*8:count*16],dtype='>f4').reshape(count,2).astype(np.float32)
    new=np.frombuffer(second[count*8:count*16],dtype='>f4').reshape(count,2).astype(np.float32)
    expected=old+np.array(delta,dtype=np.float32)/np.float32(2048)
    require(np.isfinite(new).all() and np.isfinite(old).all(),'finite UV')
    error=np.abs(new-expected);ulp=np.abs(np.spacing(expected))
    require((error<=ulp).all(),'UV translation within one float32 ULP')
    return float(error.max())


def verify(first_artifacts,second_artifacts):
    a=json.loads((first_artifacts/'report.json').read_text());b=json.loads((second_artifacts/'report.json').read_text())
    original=json.loads(a['officialExport']['packedStateJson']);e=b['officialExport'];packed=json.loads(e['packedStateJson'])
    p=e['repack']['layoutBefore']['placements'][0];q=e['packedAtlasLayout']['placements'][0];delta=[q[2]-p[2],q[3]-p[3]]
    require(len(original['meshes'])==len(packed['meshes'])==len(e['repack']['before']['meshes'])==98,'complete chart inventory')
    maximum=0;vertices=0;triangles=0;rows=[]
    for i,(old,before,new) in enumerate(zip(original['meshes'],e['repack']['before']['meshes'],packed['meshes'])):
        require(old['id']==before['id']==new['id'] and old['geometrySha256']==before['geometrySha256'],'actual accepted initial packing source')
        name=f'packed-source/{i}.bin';first=(first_artifacts/name).read_bytes();second=(second_artifacts/name).read_bytes()
        require(hashlib.sha256(first).hexdigest()==old['geometrySha256'] and hashlib.sha256(second).hexdigest()==new['geometrySha256'],'complete file identities')
        require(old['vertexCount']==new['vertexCount'] and old['triangleCount']==new['triangleCount'] and len(first)==old['vertexCount']*16+old['triangleCount']*12,'unchanged complete source dimensions')
        maximum=max(maximum,compare(first,second,old['vertexCount'],delta));vertices+=old['vertexCount'];triangles+=old['triangleCount']
        rows.append(dict(id=old['id'],beforeSha256=old['geometrySha256'],afterSha256=new['geometrySha256']))
    return dict(status='PASS_NATIVE_REPACK_FULL_SOURCE_XY_INDICES_AND_UV_TRANSLATION',meshes=98,vertices=vertices,triangles=triangles,sourceXYExact=True,fullIndicesExact=True,uvTranslationMaxError=maximum,uvTolerance='one float32 ULP of translated UV',translationPixels=delta,rows=rows,pixelCorrectnessAccepted=False)
