#!/usr/bin/env python3
"""CI smoke: inspect real packages against a clearly synthetic QA ledger receipt (no publication)."""
import base64
import json
import os
import shutil
import sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'scripts/release'))
from turboism_release.build_identity import identity,verify_receipt

receipt=dict(identity(os.environ['TURBOISM_BUILD_VERSION'],os.environ['TURBOISM_SOURCE_REVISION'],'1',1),
             buildNumber=int(os.environ['TURBOISM_BUILD_NUMBER']))
path=ROOT/'build/windows-installer/build-identity.json'
path.write_text(json.dumps(receipt))
class QALedger:
    def api(self,resource):
        assert resource == 'contents/entries/1-1.json?ref=build-ledger'
        return {'encoding':'base64','content':base64.b64encode(json.dumps(receipt).encode()).decode()}
verified=verify_receipt(QALedger(),ROOT,path.parent,receipt['version'],receipt['sourceRevision'],'1',1)
assert verified==receipt
report={'scope':'synthetic QA ledger; no official allocation or public release','receipt':receipt,'packagedIdentityVerified':True}
if receipt['channel'] in ('beta','nightly'):
    from turboism_release.prerelease import prepare,verify_candidate
    bundle=ROOT/'build/qa-candidate'
    bundle.mkdir()
    shutil.copytree(path.parent/'dist',bundle/'dist')
    (bundle/'build-identity.json').write_text(json.dumps(receipt))
    prepared=prepare(QALedger(),ROOT,bundle,receipt['sourceRevision'],'1',1,receipt['version'])
    manifest,checked,notes=verify_candidate(QALedger(),ROOT,bundle,receipt['sourceRevision'],'1',1,receipt['channel'])
    assert checked==receipt and manifest==prepared['manifest']
    (bundle/'release-notes.md').write_text(notes+'tampered')
    try:
        verify_candidate(QALedger(),ROOT,bundle,receipt['sourceRevision'],'1',1,receipt['channel'])
    except ValueError:
        report['tamperedNotesRejected']=True
    else:
        raise AssertionError('Modified candidate notes were accepted')
    (bundle/'release-notes.md').write_text(notes)
    report['prereleaseCandidateVerified']=True
print(json.dumps(report))
