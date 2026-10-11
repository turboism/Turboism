"""Mutate an actual synthetic capture report; never create native acceptance."""
import copy
import json
from pathlib import Path
import runpy
import sys

verify = runpy.run_path(str(Path(__file__).resolve().parents[1] / 'verify-surface-geometry.py'))['verify_planar']
report = json.loads(Path(sys.argv[1]).read_text())
glb = Path(sys.argv[2]).read_bytes()
verify(report, glb)
index = next(i for i, row in enumerate(report['samples']) if 'priming' in row)

def reject(name, mutate):
    candidate = dict(report)
    candidate['samples'] = list(report['samples'])
    candidate['samples'][index] = copy.deepcopy(report['samples'][index])
    mutate(candidate, candidate['samples'][index])
    try:
        verify(candidate, glb)
    except (ValueError, KeyError):
        print('REJECT', name)
        return
    raise AssertionError('accepted ' + name)

reject('missing priming', lambda c,r: r.pop('priming'))
reject('wrong protocol', lambda c,r: c.update(historyProtocol='POSE_DRIVER_UNDO_V1'))
reject('unchanged priming pose', lambda c,r: r['priming'].update(pose=r['pose']))
reject('priming history changed', lambda c,r: r['priming']['historyObserved'].update(revision=-1))
reject('inverse action wrong', lambda c,r: r['historyDriven']['entries'][-1]['action'].update(after='1.0'))
reject('missing priming meshes', lambda c,r: r['priming'].update(meshes=[]))
reject('stale priming pass', lambda c,r: r['priming']['meshes'][0]['actual']['consumed'].update(
    updateVersion=r['priming']['meshes'][0]['requested']['consumed']['updateVersion']))
reject('stale return pass', lambda c,r: r['meshes'][0]['actual']['consumed'].update(
    updateVersion=r['meshes'][0]['requested']['consumed']['updateVersion']))
print('PASS 8 rejection controls; synthetic report only')
