#!/usr/bin/env python3
"""Verify four real-menu pre-authoring refusals; process admission remains separate."""
import hashlib
from pathlib import Path

FIXTURES=(('intersecting-sheets.glb','dfae2a74e43fb58ac7faf76ab43230fa84293df8f1dc16017665a147da6232fc','closed oriented two-manifold edge required'),
          ('cyclic-sheets.glb','6374ee20102596be9f0d9fb25536b9631ccb0202471acf125ffedbd2002f474a','source Warp candidate requires single-sided primitives'))
APPEARANCES=('保留原材质','按面区分颜色')


def require(ok,why):
    if not ok:raise ValueError('MENU_REJECTION: '+why)


def verify(report,run,artifacts):
    r=report
    require(r['schemaVersion']=='mi3d-menu-rejection-v1' and r['scope']=='REAL_MENU_PREAUTHORING_REJECTION_ONLY'
            and r['runId']==run and r['status']=='PASS' and 'failure' not in r,'explicit report identity/scope/status')
    require(r['seedDocumentId']!=r['targetDocumentId'] and r['targetDocumentId'],'separate task document')
    require(r['renderVerification']==r['saveReopen']=='NOT_RUN','no renderer/persistence claim')
    rows=r['rejections'];require(len(rows)==4,'complete two-input two-mode matrix')
    baseline=rows[0]['before'];history=rows[0]['historyBefore'];names=[]
    require(set(baseline)=={'meshes','meshMetadata','parameters','rawImages','imageGroups','parts','deformers','atlases','canvas'},'complete observed authoring/resource domains')
    require(baseline['canvas']==[1024,1024] and all(baseline[k]==[] for k in ('meshes','meshMetadata','rawImages','imageGroups','deformers','atlases')),'empty task target and fixed canvas')
    require(history['availability']=='AVAILABLE' and history['document'] and history['manager'] and history['generation']>0,'available bound native history')
    for (name,digest,message),first,second in zip(FIXTURES,rows[::2],rows[1::2]):
        file=artifacts/name;require(not any(p.is_symlink() for p in (file,*file.parents)) and file.is_file() and 0<file.stat().st_size<=1024*1024,'regular bounded selected input')
        require(hashlib.sha256(file.read_bytes()).hexdigest()==digest,'actual selected source bytes')
        names.append(name)
        for row,appearance in zip((first,second),APPEARANCES):
            require(row['input']==name and row['inputSha256']==digest and row['appearance']==appearance,'exact input/mode matrix row')
            require(row['status']=='REJECTED_BEFORE_AUTHORING' and row['menuInvoked'] is True and row['realChooserApproved'] is True,'actual menu/chooser/refusal')
            require(row['errorTitle']=='无法导入 3D 模型' and row['errorMessage']==(message if appearance==APPEARANCES[0] else 'nonconvex or inward-facing triangle'),'explicit frozen production refusal reason')
            require(row['before']==row['after']==baseline and row['stateUnchanged'] is True,'complete authoring/resources unchanged')
            require(row['historyBefore']==row['historyAfter']==history and row['historyUnchanged'] is True
                    and row['canUndoBefore']==row['canUndoAfter'] and row['canRedoBefore']==row['canRedoAfter'],'full history including revision/entries/redo unchanged')
            require(row['inputUnchanged'] is True,'source remains unchanged')
    require({p.name for p in artifacts.iterdir()}==set(names)|{'report.json'},'exact rejected artifact inventory')
    return dict(status='FOUR_REAL_MENU_STRUCTURAL_REFUSALS_CONTENT_CHECKED_NOT_PROCESS_ACCEPTANCE',cases=4,inputs=names,observedAuthoringAndHistoryUnchanged=True,scope='rejection of these exact inputs in two appearance modes; no supported intersecting/cyclic import claim',pixelCorrectnessAccepted=False,goalComplete=False)
