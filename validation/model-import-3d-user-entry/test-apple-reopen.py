#!/usr/bin/env python3
"""Synthetic pair integrity checks built from a frozen capture, not an actual reopen."""
import argparse
import copy
import hashlib
import json
import runpy
import shutil
from pathlib import Path

p = argparse.ArgumentParser()
p.add_argument('--capture', type=Path, required=True)
p.add_argument('--prepared', type=Path, required=True)
p.add_argument('--out', type=Path, required=True)
a = p.parse_args()
a.out.mkdir(parents=True, exist_ok=False)
save_root, reopen_root = a.out / 'save', a.out / 'reopen'
save_root.mkdir()
reopen_root.mkdir()
for name in ('apple-locals', 'source-texture-render'):
    shutil.copytree(a.capture / name, save_root / name)
shutil.copytree(a.capture / 'source-texture-render', reopen_root / 'source-texture-render')
(save_root / 'persistence').mkdir()
raw = b'Synthetic only; not a Cubism engineering file.'
(save_root / 'persistence/mi3d-apple-model.cmo3').write_bytes(raw)
saved = json.loads((a.capture / 'report.json').read_bytes())
saved.update(schemaVersion='mi3d-apple-menu-save-v1', scope='REAL_MENU_IMPORT_SAVE_ONLY',
             saveReopen='SAVED_REOPEN_NOT_RUN')
history = saved['sourceTextureRender']['restoredHistory']
saved['persistenceSave'] = dict(commandStatus='EXECUTED', maximumBytes=256 * 1024**2,
    artifact='persistence/mi3d-apple-model.cmo3', artifactBytes=len(raw),
    artifactSha256=hashlib.sha256(raw).hexdigest(), before=saved['imported'], after=saved['imported'],
    historyBefore=history, historyAfter=history, historyUnchanged=True)
reopened = dict(schemaVersion='mi3d-apple-menu-reopen-v1', scope='OBSERVE_REOPENED_SEED_ONLY',
    runId='synthetic-reopen', status='PASS', saveReopen='REOPENED_SEED_CAPTURED_REQUIRES_PAIR_CHECK',
    reopened=saved['imported'], afterReopenCapture=saved['imported'],
    sourceTextureRender=saved['sourceTextureRender'])
verify = runpy.run_path(str(Path(__file__).with_name('verify-apple-reopen.py')))['verify']
def run(r, run_id='synthetic-reopen'):
    return verify(saved, r, saved['runId'], run_id, a.prepared, save_root, reopen_root)
assert run(reopened)['goalComplete'] is False
checks = []
def reject(name, mutate, run_id='synthetic-reopen'):
    r = copy.deepcopy(reopened)
    mutate(r)
    try:
        run(r, run_id)
    except ValueError:
        checks.append(name)
        return
    raise AssertionError('accepted ' + name)
reject('same process', lambda r: None, saved['runId'])
reject('wrong reopen schema', lambda r: r.update(schemaVersion='mi3d-apple-user-entry-v1'))
reject('wrong scope', lambda r: r.update(scope='REAL_MENU_IMPORT_SAVE_ONLY'))
reject('wrong run', lambda r: r.update(runId='foreign'))
reject('mutated reopened state', lambda r: r['reopened']['parts'].append(['foreign']))
reject('control corruption', lambda r: r['afterReopenCapture']['deformers'][0].update(authoringSha256='0'*64))
reject('capture not restored', lambda r: r['sourceTextureRender'].update(restored=False))
reject('stale consumption', lambda r: r['sourceTextureRender']['frames'][0]['lastObservation'][0]['consumed'].update(version=r['sourceTextureRender']['frames'][0]['requested'][0]['consumed']['version']))
file = reopen_root / 'source-texture-render/0.png'
original = file.read_bytes()
file.write_bytes(b'corrupted')
reject('changed reopened pixels', lambda r: None)
file.write_bytes(original)
(a.out / 'tests.json').write_text(json.dumps(dict(status='SYNTHETIC_PAIR_GATE_NOT_NATIVE', rejected=checks), indent=2)+'\n')
print(len(checks), 'pair gate refusals PASS; hostExecuted=false')
