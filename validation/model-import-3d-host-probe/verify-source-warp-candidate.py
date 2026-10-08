#!/usr/bin/env python3
"""Independent full-source candidate coordinates; not production visibility/material admission."""
import hashlib,math,struct,runpy
from pathlib import Path
FIXTURE_SHA='9320e7ae7a44f905cfab5827fe84f334b370e7e0f3ea9057fe05c01e4bd69360'
FIXTURE_BYTES=73575376
KEYS=[-30+3.75*i for i in range(17)]
POSES=[(y,p) for y in (-30,0,30) for p in (-30,0,30)]+[(-13.125,9.375),(16.875,-24.375)]
def require(ok,why):
    if not ok:raise ValueError(why)
def sha(data):return hashlib.sha256(data).hexdigest()
def fixture(raw):
    require(len(raw)==FIXTURE_BYTES and sha(raw)==FIXTURE_SHA,'authorized candidate identity')
    view=memoryview(raw);require(raw[:8]==b'MI3DAW01' and struct.unpack_from('>4i',raw,8)==(238,119,2,289),'candidate header')
    counts=[struct.unpack_from('>2i',raw,24+i*8) for i in range(2)];require(counts==[(65535,97235),(35555,53549)],'source mesh counts')
    offset=40;meshes=[]
    for n,t in counts:
        xyz=list(struct.iter_unpack('>3f',view[offset:offset+n*12]));offset+=n*12
        uv=view[offset:offset+n*8];offset+=n*8
        local=view[offset:offset+n*8];offset+=n*8
        indices=view[offset:offset+t*12];offset+=t*12
        h=hashlib.sha256();h.update(local);h.update(uv);h.update(indices)
        slots=list(struct.iter_unpack('>3i',view[offset+n*8:offset+n*20]))
        image=bytearray();shader=bytearray()
        for (u,v),(component,_,_) in zip(struct.iter_unpack('>2f',uv),slots):
            x,y=struct.unpack('>2f',struct.pack('>2f',component*512+1+256*u,1+128*v))
            image.extend(struct.pack('>2f',x,y));shader.extend(struct.pack('>2f',x/1024,y/256))
        textured=sha(image+shader+indices)
        offset+=n*20+t*4 # Original vertex/UV/slot identities and source triangle identity.
        meshes.append({'xyz':xyz,'geometrySha256':h.hexdigest(),'count':n,'texturedGeometrySha256':textured})
    require(list(struct.iter_unpack('>2f',view[offset:offset+289*8]))==[(y,p) for y in KEYS for p in KEYS],'candidate poses')
    offset+=289*8;require(offset+289*28680*8==len(raw),'candidate length')
    return meshes,{(y,p):sha(view[offset+i*28680*8:offset+(i+1)*28680*8]) for i,(y,p) in enumerate((y,p) for y in KEYS for p in KEYS)}
def verify(report,run_id,raw,captures,pixels=None):
    textured=report.get("mi3dMode")=="source-warp-textured"
    pixels={} if pixels is None else pixels
    meshes,controls=fixture(raw)
    require(report['runId']==run_id and report['mi3dMode'] in ('source-warp-candidate','source-warp-textured') and report['status']=='PASS' and report['failure'] is None,'candidate run/result')
    require(report['finallyRestored'] is True and report['initialCounts']==report['finallyCounts'],'final counts restoration')
    require({c['id'] for c in report['checks']}=={'newCommand','newIdentity'} and all(c['status']=='PASS' and c['actual'] is True for c in report['checks']),'new task document')
    s=report['sourceWarpCandidate'];require(s['status']=='PASS' and s['scope']==('FULL_SOURCE_WARP_TEXTURED_CAPTURE_ONLY' if textured else 'FULL_SOURCE_WARP_CANDIDATE_GEOMETRY_ONLY') and s['visibilityAcceptance'] is False,'candidate-only scope')
    require(s['fixtureSha256']==FIXTURE_SHA and s['vertices']==[65535,35555] and s['triangles']==[97235,53549],'source identity/counts')
    require(s['stages']==(['IDENTITY','MATERIAL','ATTACHED','NEUTRAL','BOUND','BAKED'] if textured else ['IDENTITY','ATTACHED','NEUTRAL','BOUND','BAKED']) and s['assemblyOutcome']=='COMMITTED','full assembly stages')
    require(s['sourceMeshSha256']==[m['texturedGeometrySha256' if textured else 'geometrySha256'] for m in meshes],'native local/UV/indices provenance')
    require(s['storedForms']==289 and s['controlPointsPerForm']==28680 and len(s['forms'])==289,'complete stored controls')
    axes={a['id']:a for a in s['axes']};require(set(axes)=={'ParamMI3DSourceWarpYaw','ParamMI3DSourceWarpPitch'} and all(a['keys']==KEYS for a in axes.values()),'full axes')
    guid_to_id={a['guid']:k for k,a in axes.items()};seen=set();form_guids=set()
    for f in s['forms']:
        coords={guid_to_id[g]:v for g,v in f['coordinates']};require(len(coords)==len(f['coordinates'])==2,'complete GUID coordinates')
        pose=(coords['ParamMI3DSourceWarpYaw'],coords['ParamMI3DSourceWarpPitch'])
        require(pose not in seen and f['guid'] not in form_guids and controls.get(pose)==f['xySha256'],'independent stored control hash')
        seen.add(pose);form_guids.add(f['guid'])
    require(seen==set(controls),'joint grid completeness')
    require(s['historyAfter']['position']==s['historyBefore']['position']+1 and s['historyAfterUndo']['position']==s['historyBefore']['position'] and s['redoSourceMeshesAndWarpExact'] is True,'native Undo/Redo')
    require(len(s['samples'])==22 and {(tuple(r['pose']),r['mesh']) for r in s['samples']}=={(p,i) for p in POSES for i in range(2)},'calculated pose/mesh inventory')
    require(set(captures)=={f'source-warp-capture/sample-{i}.xy' for i in range(22)},'raw native capture inventory')
    maximum=0
    for index,r in enumerate(s['samples']):
        require(r['capture']==f'source-warp-capture/sample-{index}.xy' and r['coordinateType']=='Canvas','capture identity/space')
        data=captures[r['capture']];mesh=meshes[r['mesh']]
        require(len(data)==mesh['count']*8 and sha(data)==r['xySha256'],'native capture length/hash')
        y,p=map(math.radians,r['pose']);cy,sy,cp,sp=math.cos(y),math.sin(y),math.cos(p),math.sin(p)
        error=0
        for (x,y0,z),(a,b) in zip(mesh['xyz'],struct.iter_unpack('>2f',data)):
            require(math.isfinite(a) and math.isfinite(b),'finite native coordinates')
            yp=cp*(y0-130.6125549674034)-sp*z;zp=sp*(y0-130.6125549674034)+cp*z
            expected=(512+2.954462184876577*(cy*x+sy*zp),512-2.954462184876577*yp)
            error=max(error,math.hypot(a-expected[0],b-expected[1]))
        # Candidate sampling explores the ~0.643px ideal fit bound, not a production tolerance claim.
        require(error<=1 and abs(error-r['maxErrorPixels'])<.0001,'independent full source projection')
        maximum=max(maximum,error)
        for family in ('live','consumed'):
            values={v['id']:v['value'] for v in r[family]['parameters']}
            require(values['ParamMI3DSourceWarpYaw']==r['pose'][0] and values['ParamMI3DSourceWarpPitch']==r['pose'][1],'exact native parameter '+family)
    if textured:
        require(s['textureSourceSha256']=='3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999' and s['materials']=='SOURCE_GLB_TEXTURES_SHARED_ATLAS','source texture identity/scope')
        require(s['authoredFormsAfterSamplingExact'] is True and s['redoImageInputsExact'] is True,'render/Redo changed authored Warp or image inputs')
        require(len(s['pixelFrames'])==11 and set(pixels)=={f'source-warp-pixels/{i}.png' for i in range(11)},'textured capture inventory')
        decode=runpy.run_path(str(Path(__file__).with_name('verify-pixel-sampling.py')))['_pixels']
        for i,(frame,pose) in enumerate(zip(s['pixelFrames'],POSES)):
            name=f'source-warp-pixels/{i}.png';png=pixels[name]
            require(frame['artifact']==name and tuple(frame['pose'])==pose and frame['artifactSha256']==sha(png) and frame['artifactBytes']==len(png),'textured frame identity')
            require(frame['status']=='CAPTURED_NATIVE_NOT_YET_INDEPENDENTLY_VERIFIED' and frame['historyBefore']==frame['historyAfter'],'render history/scope')
            decode(png,size=1024) # CRC/decompression/dimensions only; no color/visibility claim.
    else:
        require(not pixels and 'pixelFrames' not in s,'undeclared texture captures')
    return {'scope':s['scope'],'maxIndependentErrorPixels':maximum,'productionImportAccepted':False}
