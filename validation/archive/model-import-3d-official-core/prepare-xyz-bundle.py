#!/usr/bin/env python3
"""Freeze the exact succeeded XYZ export for standalone official Core; no launch."""
import argparse,json,runpy,shutil
from pathlib import Path
H=runpy.run_path(str(Path(__file__).with_name('prepare-bundle.py')))
plain,digest,inventory,require=map(H.get,('plain','digest','inventory','require'))
def build(controller,recorder,editor,out):
 controller,recorder,editor,out=map(plain,(controller,recorder,editor,out));root=controller/'native-result'
 outcome=json.loads((root/'queue/outcome.json').read_text());report=json.loads((root/'state/report.json').read_text());review=json.loads((controller/'independent-export-review.json').read_text())
 require(review['status']=='OFFICIAL_XYZ_MOC_CAPTURE_VERIFIED_CORE_PENDING' and review['jobId']==outcome['jobId'] and review['runId']==outcome['runId'] and review['prepared']==outcome['preparedDigest'],'exact reviewed XYZ export')
 require(outcome['terminalState']=='succeeded' and outcome['normalExit'] and outcome['fixtureUnchanged'] and outcome['identityVerified'] and outcome['validationStatus']=='PASS','normal official source export')
 proof=outcome['containment']['kernelProof'];require(not proof['cgroupKillWritten'] and not proof['processTimedOut'] and proof['processReturncode']==0,'unforced exporter exit')
 require(report['status']=='PASS' and report['schemaVersion']=='mi3d-apple-xyz-export-bytes-v1' and report['inputSha256']=='3b31ef1167d6aa75c5746906838ad1e37447718ece116512c187337be0f87999','exact XYZ apple source')
 section=report['officialExport'];require(section['sourceRestored'] and section['stateUnchangedAtExport'] and section['historyUnchangedAtExport'] and not section['changedStateFields'],'protected source/history')
 meshes=report['imported']['meshes'];require(len(meshes)==98 and sum(m['vertexCount'] for m in meshes)==100258 and sum(m['triangleCount'] for m in meshes)==150784,'complete original apple slots/faces')
 params=report['imported']['parameters'];axes=[]
 for name in ('Yaw','Pitch','Roll'):
  matches=[p for p in params if p[0].startswith('Param3D'+name)];require(len(matches)==1 and matches[0][1:]==[-180,0,180,0],'full neutral XYZ domain');axes.append(matches[0][0])
 meta=json.loads((recorder/'build.json').read_text());require(meta['status']=='BUILT_NOT_NATIVE_VERIFIED' and meta['coreSha256']==H['CORE_SHA'],'reviewed standalone recorder')
 require(digest(editor/'app/lib/Live2D_Cubism.jar')==H['EDITOR_SHA'],'official installation')
 copies={'Live2DCubismCore.jar':recorder/'Live2DCubismCore.jar','official-core-capture.jar':recorder/'official-core-capture.jar'}
 require(digest(copies['Live2DCubismCore.jar'])==meta['coreSha256'] and digest(copies['official-core-capture.jar'])==meta['recorderSha256'],'recorder artifacts')
 for row in [section['moc'],*section['textures']]:
  src=plain(root/'state'/row['artifact']);require(src.stat().st_size==row['bytes'] and digest(src)==row['sha256'],'official export artifact');copies[src.name]=src
 require(set(copies)=={'Live2DCubismCore.jar','official-core-capture.jar','model.moc3','texture-0.png'} and copies['model.moc3'].stat().st_size<=256*1024*1024,'bounded complete export')
 for name in H['NATIVES']:copies['native/'+name]=plain(editor/'app/dll64'/name)
 jre=plain(editor/'app/jre');before=inventory(jre);require('JAVA_VERSION="17.' in (jre/'release').read_text(),'official Java17')
 out.mkdir(parents=True,exist_ok=False)
 for name,src in copies.items():
  target=out/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(src,target);require(digest(target)==digest(src),'frozen member')
 shutil.copytree(jre,out/'jre');require(inventory(out/'jre')==inventory(jre)==before,'frozen official JRE')
 manifest=dict(schemaVersion='mi3d-official-core-bundle-v1',status='FROZEN_NOT_EXECUTED',hostExecuted=False,captureProfile='apple-xyz-140-v1',axes=axes,exportJobId=outcome['jobId'],exportRunId=outcome['runId'],exportAcceptanceSha256=digest(controller/'independent-export-review.json'),editorSha256=H['EDITOR_SHA'],coreSha256=meta['coreSha256'],recorderSha256=meta['recorderSha256'],mocSha256=section['moc']['sha256'],files=inventory(out))
 (out/'bundle.json').write_text(json.dumps(manifest,indent=2)+'\n');H['verify'](out);print(json.dumps(dict(status=manifest['status'],axes=axes,files=len(manifest['files']))))
if __name__=='__main__':
 p=argparse.ArgumentParser()
 for name in ('controller','recorder','editor','out'):p.add_argument('--'+name,type=Path,required=True)
 a=p.parse_args();build(a.controller,a.recorder,a.editor,a.out)
