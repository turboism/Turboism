#!/usr/bin/env python3
"""Fresh optimized Cat bank and finite native pose audit; no performance claim."""
import json
import math
import runpy
import sys
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
SAVE = runpy.run_path(str(HERE / 'analyze-cat-optimized-save.py'))
PAIR = SAVE['PAIR']
POSES = runpy.run_path(str(HERE / 'analyze-cat-native.py'))['POSES']


def analyze(save_root, reopen_root):
    save_root, root = Path(save_root), Path(reopen_root)
    SAVE['analyze'](save_root)
    saved_job, saved = PAIR['terminal'](save_root)
    job, report = PAIR['terminal'](root)
    assert report['schemaVersion'] == 'mi3d-cat-optimized-reopen-v1'
    assert report['scope'] == 'FRESH_EDITOR_CAT_PERSISTENCE_BANK_AND_NINE_NATIVE_FRAMES'
    assert report['inputSha256'] == SAVE['SOURCE']
    assert job['job_id'] != saved_job['job_id'] and job['run_id'] != saved_job['run_id']
    assert report['persistentBank'] == saved['persistentBank'], 'complete durable bank changed'
    admission = json.loads((root / 'admission-review.json').read_text())
    assert admission['prepared'] == job['prepared_id']
    assert admission['status'] == 'INPUT_REVIEW_PASS_NOT_EXECUTED'
    assert len(admission['checks']) >= 43 and all(c['passed'] for c in admission['checks'])
    assert saved['persistenceSave']['artifactSha256'] in (root / 'host-job/evidence/fixture-before.properties').read_text()
    assert len(report['frames']) == 9
    identities = None
    for i, (frame, pose) in enumerate(zip(report['frames'], POSES)):
        assert frame['XYZ'] == pose and frame['artifact'] == f'cat-frames/{i}.png'
        path = PAIR['read_artifact'](root, frame['artifact'])
        assert PAIR['digest'](path) == frame['sha256']
        with Image.open(path) as image:
            assert image.format == 'PNG' and image.size == (1024, 1024)
            assert image.convert('RGBA').getchannel('A').getbbox() is not None
        rows = frame['evaluated']
        current = {(r['artMeshId'], r['sourceGuid']) for r in rows}
        assert len(rows) == len(current) == 512
        if identities is None:
            identities = current
        assert current == identities
        slots = 0
        for row in rows:
            assert row['live']['values'] == row['consumed']['values']
            parameters = {r[1]: r[2] for r in row['consumed']['values']}
            for label, wanted in zip(('Pitch', 'Yaw', 'Roll'), pose):
                assert [v for n, v in parameters.items() if n.startswith('Param3D' + label)] == [wanted]
            calculated = row['calculated']
            assert calculated['coordinateType'] == 'Canvas'
            points = calculated['positions']
            slots += len(points)
            assert all(len(p) == 2 and all(math.isfinite(v) for v in p) for p in points)
        assert slots == 35397
    result = dict(status='PASS_OPTIMIZED_CAT_FRESH_REOPEN_BANK_NINE_FINITE_POSES',
                  saveJobId=saved_job['job_id'], reopenJobId=job['job_id'],
                  persistentBank=report['persistentBank'], performanceAccepted=False,
                  meshEditingAccepted=False, continuousOcclusionAccepted=False)
    (root / 'independent-optimized-reopen-review.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    analyze(*sys.argv[1:])
