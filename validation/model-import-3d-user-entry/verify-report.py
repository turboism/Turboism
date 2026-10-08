#!/usr/bin/env python3
"""Independent menu-result geometry/history content gate; process trust is separate."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import runpy
import struct

G=runpy.run_path(str(Path(__file__).resolve().parents[1]/'model-import-3d-host-probe/verify-cube-geometry.py'))


def require(ok,message):
    if not ok:raise ValueError('USER_ENTRY: '+message)


TEXTURED_SHA='dee63d3d8dcf9f08adf302c6020486385e71929a81dfc674fa2c6aefbe997859'
PNG_SHA='a724382bcfdf3e706d64d5cf57646ac28eb00977bd95b20ce5b1323c2572d10f'

def textured_source(cube,texture):
    G['cube_source'](cube) # Pins the original authorized geometry input.
    require(Path(texture).stat().st_size<=16*1024*1024,'textured input budget')
    raw=Path(texture).read_bytes()
    require(hashlib.sha256(raw).hexdigest()==TEXTURED_SHA,'textured input identity')
    def unpack(raw):
        magic,version,total,n,kind=struct.unpack_from('<5I',raw)
        require((magic,version,total,kind)==(0x46546c67,2,len(raw),0x4e4f534a),'GLB header')
        size,kind=struct.unpack_from('<2I',raw,20+n)
        require(kind==0x004e4942 and size==len(raw)-28-n,'GLB binary chunk')
        return json.loads(raw[20:20+n]),raw[28+n:]
    source,binary=unpack(Path(cube).read_bytes());doc,pixels=unpack(raw)
    require(doc['meshes']==source['meshes'] and doc['accessors']==source['accessors']
            and doc['bufferViews'][:len(source['bufferViews'])]==source['bufferViews']
            and pixels[:len(binary)]==binary,'textured source geometry/UV preservation')
    material=doc['materials'][0]['pbrMetallicRoughness']
    require(material['baseColorFactor']==[1,1,1,1] and material['baseColorTexture']=={'index':0,'texCoord':0},'source material')
    view=doc['bufferViews'][doc['images'][0]['bufferView']]
    png=pixels[view['byteOffset']:view['byteOffset']+view['byteLength']]
    require(hashlib.sha256(png).hexdigest()==PNG_SHA,'embedded texture identity')


def verify(r,cube,run,texture=None,*,padded=False):
    require(not padded or texture is not None,'padded layout requires source texture')
    require(r.get('textureLayout','ORIGINAL')==('EDGE_PADDED_POT' if padded else 'ORIGINAL'),'explicit texture layout')
    dimensions=[512,256] if padded else [256,128]
    require(r['schemaVersion']=='mi3d-user-entry-v1' and r['scope']=='REAL_MENU_IMPORT_UNDO_REDO_ONLY'
            and r['status']=='PASS' and 'failure' not in r and r['runId']==run,'report identity/status')
    require(r['seedDocumentId']!=r['targetDocumentId'] and r['seedDocumentId'] and r['targetDocumentId'],'task document')
    require(r['menuInvoked'] is True and r['realChooserApproved'] is True and r['appearance']==('SOURCE_MATERIAL' if texture is not None else 'FACE_COLORS'),'real UI stages')
    require(all(s in r['confirmation'] for s in ('6 个网格','12150','45 / 45'))
            and r['completionMessage'].startswith('导入完成'),'production dialogs')
    if texture is not None:textured_source(cube,texture)
    require(r['inputSha256']==(TEXTURED_SHA if texture is not None else G['INPUT_SHA256']),'authorized input')
    base,imported=r['baseline'],r['imported']
    require(r['undoRestored'] is True and r['afterUndo']==base
            and r['redoRestored'] is True and r['afterRedo']==imported,'observed Undo/Redo state')
    before=r['historyBefore']
    require(type(before) is int and before>=0 and r['historyAfter']==before+1
            and r['historyAfterUndo']==before and r['historyAfterRedo']==before+1,'single native history step')
    require(base['meshes']==[] and base['meshMetadata']==[] and base['rawImages']==[]
            and base['imageGroups']==[],'blank baseline')
    original={p[0]:p for p in base['parameters']};parameters={p[0]:p for p in imported['parameters']}
    require(len(original)==len(base['parameters']) and len(parameters)==len(imported['parameters'])
            and all(parameters.get(k)==v for k,v in original.items()),'existing parameters changed')
    added=set(parameters)-set(original)
    yaw=[p for p in added if re.fullmatch(r'Param3DYaw[0-9a-f]{12}',p)]
    pitch=[p for p in added if re.fullmatch(r'Param3DPitch[0-9a-f]{12}',p)]
    require(len(added)==2 and len(yaw)==len(pitch)==1 and yaw[0][-12:]==pitch[0][-12:],'generated parameter identities')
    for p in added:require(parameters[p][1:]==[-30,0,30,0],'import parameter domain/neutral')
    snapshots=imported['meshes'];ids=[s['artMeshId'] for s in snapshots]
    require(len(ids)==len(set(ids))==6,'six unique meshes')
    source,patches=G['cube_source'](cube)
    for s,vertices,patch in zip(snapshots,source,patches):
        # Rename only random public axis IDs to the oracle's fixed labels after
        # checking their actual definitions. No geometry/UV fitting or reordering.
        normalized=copy.deepcopy(s);mapping={yaw[0]:G['PARAMETERS'][0],pitch[0]:G['PARAMETERS'][1]}
        require({a['id'] for a in s['axes']}==set(mapping),'mesh parameter dependency')
        for axis in normalized['axes']:axis['id']=mapping[axis['id']]
        expected=copy.deepcopy(patch)
        if texture is None:expected['uvs']=[[G['f32'](.25+.5*u),G['f32'](.25+.5*v)] for u,v in patch['uvs']]
        if padded:expected['uvs']=[[G['f32']((256*u+1)/512),G['f32']((128*v+1)/256)] for u,v in patch['uvs']]
        G['stored'](normalized,vertices,True,expected,texture_source=True,texture_size=dimensions)
        require(all(f['drawOrder']==500 for f in s['forms']),'convex face ordering')
    metadata=imported['meshMetadata']
    require(len(metadata)==6 and [m['id'] for m in metadata]==ids,'mesh metadata correspondence')
    images={i[0] for g in imported['imageGroups'] for i in g['images']}
    bindings=[m['input'].get('image') for m in metadata]
    count=1 if texture is not None else 6
    require(len(imported['rawImages'])==count and len(images)==len(set(bindings))==count and set(bindings)==images,'source material resource sharing')
    require(all(i[2:]==dimensions for i in imported['rawImages'])
            and all(i[2:]==dimensions for g in imported['imageGroups'] for i in g['images']),'source image dimensions')
    require(all(m['culling'] is True and m['visible'] is True and m['input']['kind']=='MODEL_IMAGE'
                and m['input']['atlas']=='' for m in metadata),'native visible culled image binding')
    parts={p[0] for p in imported['parts']}-{p[0] for p in base['parts']}
    require(len(parts)==1 and {m['parent'] for m in metadata}==parts,'one control part')
    return dict(status='UI_REPORT_CHECKED_NOT_PROCESS_ADMITTED',runId=run,meshes=6,forms=12150,
                axesKeys=[45,45],appearance=r['appearance'],notCovered=['process and bundle admission','rendered face colors','save/reopen'],goalComplete=False)


if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);p.add_argument('--cube',type=Path,required=True)
    p.add_argument('--texture',type=Path);p.add_argument('--padded',action='store_true');p.add_argument('--run',required=True);p.add_argument('--out',type=Path,required=True);a=p.parse_args()
    require(a.report.stat().st_size<=64*1024*1024,'report budget')
    def pairs(items):
        result={}
        for k,v in items:require(k not in result,'duplicate JSON key');result[k]=v
        return result
    raw=a.report.read_bytes();r=json.loads(raw,object_pairs_hook=pairs,parse_constant=lambda v:(_ for _ in ()).throw(ValueError(v)))
    result=verify(r,a.cube,a.run,a.texture,padded=a.padded);result['reportSha256']=hashlib.sha256(raw).hexdigest()
    with a.out.open('x') as f:json.dump(result,f,indent=2);f.write('\n')
    print(result['status'])
