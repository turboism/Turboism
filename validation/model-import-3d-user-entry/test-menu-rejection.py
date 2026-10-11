#!/usr/bin/env python3
"""Synthetic refusal-report corruption tests; not native evidence."""
import copy,json,runpy,shutil,tempfile
from pathlib import Path

module=runpy.run_path(str(Path(__file__).with_name('verify-menu-rejection.py')));verify=module['verify'];run='queue-'+'0'*32
root=Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='mi3d-menu-refusal-') as name:
    artifacts=Path(name);(artifacts/'report.json').write_text('{}')
    baseline=dict(meshes=[],meshMetadata=[],parameters=[],rawImages=[],imageGroups=[],parts=[],deformers=[],atlases=[],canvas=[1024,1024])
    history=dict(availability='AVAILABLE',position=0,generation=1,revision=0,entries=[],document='history-document-1',manager='history-manager-1')
    rows=[]
    for (name,digest,reason),folder in zip(module['FIXTURES'],('intersecting-sheets','cyclic-sheets')):
        shutil.copyfile(root/f'artifacts/{folder}/sheets.glb',artifacts/name)
        for appearance in module['APPEARANCES']:
            rows.append(dict(input=name,inputSha256=digest,appearance=appearance,status='REJECTED_BEFORE_AUTHORING',menuInvoked=True,realChooserApproved=True,
                errorTitle='无法导入 3D 模型',errorMessage=reason if appearance=='保留原材质' else 'nonconvex or inward-facing triangle',before=baseline,after=baseline,historyBefore=history,historyAfter=history,
                canUndoBefore=False,canUndoAfter=False,canRedoBefore=False,canRedoAfter=False,stateUnchanged=True,historyUnchanged=True,inputUnchanged=True))
    base=json.loads(json.dumps(dict(schemaVersion='mi3d-menu-rejection-v1',scope='REAL_MENU_PREAUTHORING_REJECTION_ONLY',runId=run,status='PASS',seedDocumentId='seed',targetDocumentId='new',renderVerification='NOT_RUN',saveReopen='NOT_RUN',rejections=rows)))
    verify(base,run,artifacts)
    cases=[('missing-mode',lambda r:r['rejections'].pop()),('wrong-input',lambda r:r['rejections'][0].update(inputSha256='0'*64)),
           ('no-menu',lambda r:r['rejections'][0].update(menuInvoked=False)),('no-chooser',lambda r:r['rejections'][0].update(realChooserApproved=False)),
           ('wrong-reason',lambda r:r['rejections'][0].update(errorMessage='missing texture')),
           ('changed-mesh',lambda r:r['rejections'][0]['after']['meshes'].append({'id':'unexpected'})),
           ('created-warp',lambda r:r['rejections'][0]['after']['deformers'].append({'id':'unexpected'})),
           ('created-atlas',lambda r:r['rejections'][0]['after']['atlases'].append(['atlas'])),
           ('history-revision',lambda r:r['rejections'][0]['historyAfter'].update(revision=1)),
           ('history-entry',lambda r:r['rejections'][0]['historyAfter']['entries'].append('new')),
           ('redo-changed',lambda r:r['rejections'][0].update(canRedoAfter=True)),
           ('wrong-document',lambda r:r.update(targetDocumentId='seed')),
           ('render-claim',lambda r:r.update(renderVerification='PASS'))]
    for name,mutate in cases:
        changed=copy.deepcopy(base);mutate(changed)
        try:verify(changed,run,artifacts)
        except ValueError:continue
        raise AssertionError('accepted '+name)
    (artifacts/'persistence').mkdir()
    try:verify(base,run,artifacts)
    except ValueError:pass
    else:raise AssertionError('accepted saved artifact namespace')
    print('PASS14 synthetic corruptions; NOT_NATIVE_ACCEPTANCE')
