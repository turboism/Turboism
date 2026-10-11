#!/usr/bin/env python3
"""Optimized Cat native SAVE_AS gate. Reopen, editing and rendering remain separate."""
import hashlib,json,runpy,sys
from pathlib import Path
HERE=Path(__file__).resolve().parent
PAIR=runpy.run_path(str(HERE/'analyze-derived-persistence.py'))
SOURCE='ed1a27af5ee2724849d8c6ed3e76322579c3c88d92ecb11ffd99213ee0125d11'

def require(ok,message):
    if not ok:raise ValueError('OPTIMIZED_CAT_SAVE: '+message)

def validate_report(report):
    require(report['schemaVersion']=='mi3d-cat-optimized-save-v1','dedicated optimized schema')
    require(report['scope']=='REAL_CAT_MENU_IMPORT_NATIVE_SAVE_BANK_ONLY_NO_REPEATED_HISTORY_OR_FRAMES','save-only scope')
    require(report['inputSha256']==SOURCE and report['status']=='PASS','exact original ZIP and PASS')
    require('frames' not in report and report['importHistoryProbe'] is False,'no mixed history/render acceptance')
    require('源模型 70576 面 → 导入 48864 面' in report['confirmation'],'actual production optimization confirmation')
    for label in ('imported','persistentBank'):
        bank=report[label]
        require(bank['meshes']==512 and bank['slots']==35397 and bank['triangles']==48864,'complete optimized native inventory')
        sha=bank['sha256'];require(isinstance(sha,str) and len(sha)==64 and all(c in '0123456789abcdef' for c in sha),'complete bank digest')
    saved=report['persistenceSave']
    require(saved['commandStatus']=='EXECUTED' and saved['artifact']=='persistence/mi3d-cat-model.cmo3','typed SAVE_AS and exact new artifact')
    require(type(saved['artifactBytes']) is int and 0<saved['artifactBytes']<=256*1024*1024,'bounded actual save')
    return report['persistentBank'],saved

def analyze(root):
    root=Path(root);job,report=PAIR['terminal'](root)
    admission=json.loads((root/'admission-review.json').read_text())
    require(admission['status']=='INPUT_REVIEW_PASS_NOT_EXECUTED' and len(admission['checks'])>=52 and all(c['passed'] is True for c in admission['checks']),'complete explicit admission')
    require(job['prepared_id']==admission['prepared'] and job['request_key']==root.name and root.name.startswith('cat-optimized-save-native-r'),'exact admitted job')
    bank,saved=validate_report(report);path=PAIR['read_artifact'](root,saved['artifact'])
    require(path.stat().st_size==saved['artifactBytes'] and PAIR['digest'](path)==saved['artifactSha256'],'artifact bytes/hash match native save')
    with path.open('rb') as stream:require(stream.read(4)==b'CAFF','Cubism archive header')
    result=dict(status='PASS_OPTIMIZED_CAT_NATIVE_SAVE_ONLY',jobId=job['job_id'],preparedId=job['prepared_id'],sourceArchiveSha256=SOURCE,persistentBank=bank,artifact=saved,freshReopenAccepted=False,meshEditingAccepted=False,performanceAccepted=False,renderingAccepted=False)
    (root/'independent-optimized-save-review.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result))

if __name__=='__main__':analyze(sys.argv[1])
