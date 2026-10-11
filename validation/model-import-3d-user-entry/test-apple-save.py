#!/usr/bin/env python3
"""Synthetic save gate refusals; no native persistence or pixel acceptance."""
import argparse
import copy
import hashlib
import json
import runpy
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=False)
(a.out / 'persistence').mkdir()
file = a.out / 'persistence/mi3d-apple-model.cmo3'
raw = b'Synthetic only; this is not a Cubism model.'
file.write_bytes(raw)
state = dict(meshes=['source'], deformers=['all controls'], parameters=['dense'], images=['source'])
history = dict(position=1, document='task', revision=4, entries=['import'])
r = dict(schemaVersion='mi3d-apple-menu-save-v1', scope='REAL_MENU_IMPORT_SAVE_ONLY',
         saveReopen='SAVED_REOPEN_NOT_RUN', imported=state,
         sourceTextureRender=dict(restoredHistory=history),
         persistenceSave=dict(commandStatus='EXECUTED', maximumBytes=256 * 1024**2,
             artifact='persistence/mi3d-apple-model.cmo3', artifactBytes=len(raw),
             artifactSha256=hashlib.sha256(raw).hexdigest(), before=state, after=state,
             historyBefore=history, historyAfter=history, historyUnchanged=True))
verify = runpy.run_path(str(Path(__file__).with_name('verify-apple-save.py')))['verify_save']
verify(r, a.out)
checks = []


def reject(name, mutate):
    q = copy.deepcopy(r)
    mutate(q)
    try:
        verify(q, a.out)
    except ValueError:
        checks.append(name)
        return
    raise AssertionError('accepted ' + name)


for key, bad in [('schemaVersion', 'wrong'), ('scope', 'wrong'), ('saveReopen', 'NOT_RUN')]:
    reject(key, lambda q, k=key, v=bad: q.update({k: v}))
for key, bad in [('commandStatus', 'FAILED'), ('maximumBytes', 512 * 1024**2),
                 ('artifact', '../foreign.cmo3'), ('artifactBytes', 1),
                 ('artifactSha256', '0' * 64), ('before', {}), ('after', {}),
                 ('historyBefore', {}), ('historyAfter', {}), ('historyUnchanged', False)]:
    reject(key, lambda q, k=key, v=bad: q['persistenceSave'].update({k: v}))
file.unlink()
file.symlink_to(a.out / 'missing')
reject('linked artifact', lambda q: None)
file.unlink()
file.write_bytes(b'X' * len(raw))
reject('same-size corrupted artifact', lambda q: None)
file.unlink()
with file.open('wb') as stream:
    stream.truncate(256 * 1024**2 + 1)
reject('oversized actual artifact', lambda q: q['persistenceSave'].update(artifactBytes=file.stat().st_size))
file.unlink()
reject('missing artifact', lambda q: None)
result = dict(status='SYNTHETIC_SAVE_GATE_ONLY_NOT_NATIVE', rejected=checks)
(a.out / 'tests.json').write_text(json.dumps(result, indent=2) + '\n')
print(len(checks), 'save gate refusals PASS; hostExecuted=false')
