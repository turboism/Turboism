#!/usr/bin/env python3
"""Freeze a succeeded derived Apple export for standalone official Core."""
import argparse,json,runpy,shutil,sys
from pathlib import Path
H=runpy.run_path(str(Path(__file__).with_name('prepare-bundle.py')))
plain,digest,inventory,require=map(H.get,('plain','digest','inventory','require'))
PAIR=runpy.run_path(str(Path(__file__).parents[1]/'model-import-3d-user-entry/analyze-derived-persistence.py'))

def build(controller,recorder,editor,out):
 controller,recorder,editor,out=map(plain,(controller,recorder,editor,out))
 job,report=PAIR['terminal'](controller)
 review=json.loads((controller/'independent-derived-export-review.json').read_text())
 require(review['status']=='PASS_DERIVED_EXPORT_BYTES_BANKS_AND_ORIGINAL_FACES_ONLY'
         and review['jobId']==job['job_id'] and review['atlasUvPixelAccepted'],'reviewed complete derived export')
 require(report['schemaVersion']=='mi3d-apple-derived-export-v1','derived export protocol')
 meshes=report['imported']['meshes']
 require(len(meshes)==98 and sum(m['vertexCount'] for m in meshes)==80514
         and sum(m['triangleCount'] for m in meshes)==150784,'all derived slots and original faces')
 section=report['officialExport']
 require(section['sourceRestored'] and section['stateUnchangedAtExport']
         and section['historyUnchangedAtExport'] and not section['changedStateFields'],'protected source and export history')
 axes=[]
 for label in ('Yaw','Pitch','Roll'):
  matches=[p for p in report['imported']['parameters'] if p[0].startswith('Param3D'+label)]
  require(len(matches)==1 and matches[0][1:]==[-180,0,180,0],'full XYZ domain')
  axes.append(matches[0][0])
 meta=json.loads((recorder/'build.json').read_text())
 require(meta['status']=='BUILT_NOT_NATIVE_VERIFIED' and meta['coreSha256']==H['CORE_SHA'],'standalone recorder')
 require(digest(editor/'app/lib/Live2D_Cubism.jar')==H['EDITOR_SHA'],'official installation')
 copies={'Live2DCubismCore.jar':recorder/'Live2DCubismCore.jar','official-core-capture.jar':recorder/'official-core-capture.jar'}
 require(digest(copies['Live2DCubismCore.jar'])==meta['coreSha256']
         and digest(copies['official-core-capture.jar'])==meta['recorderSha256'],'recorder identities')
 require(section['atlasPageCount']==len(section['textures'])==2,'exact two-page derived export')
 for row in [section['moc'],*section['textures']]:
  src=plain(controller/'host-job/state'/row['artifact'])
  require(src.stat().st_size==row['bytes'] and digest(src)==row['sha256'],'export artifact identity')
  require(src.name not in copies,'unique export names');copies[src.name]=src
 require(set(copies)=={'Live2DCubismCore.jar','official-core-capture.jar','model.moc3','texture-0.png','texture-1.png'}
         and copies['model.moc3'].stat().st_size<=256*1024*1024,'bounded complete export')
 for name in H['NATIVES']:copies['native/'+name]=plain(editor/'app/dll64'/name)
 jre=plain(editor/'app/jre');before=inventory(jre)
 require('JAVA_VERSION="17.' in (jre/'release').read_text(),'official Java17')
 out.mkdir(parents=True,exist_ok=False)
 for name,src in copies.items():
  target=out/name;target.parent.mkdir(parents=True,exist_ok=True)
  shutil.copyfile(src,target);require(digest(target)==digest(src),'frozen member')
 shutil.copytree(jre,out/'jre');require(inventory(out/'jre')==inventory(jre)==before,'frozen JRE')
 manifest=dict(schemaVersion='mi3d-official-core-bundle-v1',status='FROZEN_NOT_EXECUTED',hostExecuted=False,
  captureProfile='apple-derived-xyz-140-v1',axes=axes,exportJobId=job['job_id'],exportRunId=job['run_id'],
  exportAcceptanceSha256=digest(controller/'independent-derived-export-review.json'),editorSha256=H['EDITOR_SHA'],
  coreSha256=meta['coreSha256'],recorderSha256=meta['recorderSha256'],mocSha256=section['moc']['sha256'],files=inventory(out))
 (out/'bundle.json').write_text(json.dumps(manifest,indent=2)+'\n')
 sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'scripts/preview'))
 from host_validation_official_core import bundle
 require(bundle(out)==manifest,'exact queue bundle validation')
 print(json.dumps(dict(status=manifest['status'],axes=axes,files=len(manifest['files']))))

if __name__=='__main__':
 p=argparse.ArgumentParser()
 for name in ('controller','recorder','editor','out'):p.add_argument('--'+name,type=Path,required=True)
 a=p.parse_args();build(a.controller,a.recorder,a.editor,a.out)
