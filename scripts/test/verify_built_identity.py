#!/usr/bin/env python3
"""CI smoke: inspect real packages against a clearly synthetic QA ledger receipt (no publication)."""
import base64
import json
import os
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
print(json.dumps({'scope':'synthetic QA ledger; no official allocation or public release','receipt':receipt,'packagedIdentityVerified':True}))
