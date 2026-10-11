#!/usr/bin/env python3
"""Independent apple menu/save content gate; process, reopen and export remain separate."""
import argparse
import hashlib
import json
import runpy
from pathlib import Path

BASE = runpy.run_path(str(Path(__file__).with_name('verify-apple-report.py')))
MAX_BYTES = 256 * 1024 * 1024


def require(ok, why):
    if not ok:
        raise ValueError('APPLE_SAVE: ' + why)


def verify_save(r, artifacts):
    require(r['schemaVersion'] == 'mi3d-apple-menu-save-v1'
            and r['scope'] == 'REAL_MENU_IMPORT_SAVE_ONLY'
            and r['saveReopen'] == 'SAVED_REOPEN_NOT_RUN', 'explicit save scope')
    s = r['persistenceSave']
    require(s['commandStatus'] == 'EXECUTED' and s['maximumBytes'] == MAX_BYTES,
            'typed command and bounded size policy')
    require(s['before'] == s['after'] == r['imported'], 'all authored state preserved')
    require(s['historyUnchanged'] is True and s['historyBefore'] == s['historyAfter']
            == r['sourceTextureRender']['restoredHistory'], 'full history preserved')
    require(s['artifact'] == 'persistence/mi3d-apple-model.cmo3', 'fixed target')
    file = artifacts / s['artifact']
    require(not any(p.is_symlink() for p in (file, *file.parents)), 'no linked path')
    require(file.is_file() and 0 < file.stat().st_size <= MAX_BYTES
            and file.stat().st_size == s['artifactBytes'], 'actual bounded artifact')
    digest = hashlib.sha256()
    with file.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    require(digest.hexdigest() == s['artifactSha256'], 'actual artifact bytes')
    return dict(artifact=s['artifact'], bytes=s['artifactBytes'], sha256=s['artifactSha256'])


def verify(r, run, prepared, artifacts):
    saved = verify_save(r, artifacts)
    base = dict(r, schemaVersion='mi3d-apple-user-entry-v1', scope='REAL_MENU_IMPORT_UNDO_REDO_ONLY')
    content = BASE['verify'](base, run, prepared, artifacts)
    return dict(status='APPLE_MENU_SAVE_CONTENT_CHECKED_NOT_PROCESS_OR_REOPEN_ACCEPTANCE',
                content=content, saved=saved, persistenceAccepted=False,
                officialExportAccepted=False, goalComplete=False)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    for name in ('report', 'prepared', 'artifacts', 'out'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--run', required=True)
    a = p.parse_args()
    require(not a.report.is_symlink() and a.report.stat().st_size <= 64 * 1024 * 1024,
            'bounded regular report')
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'duplicate JSON key')
            result[key] = value
        return result
    report = json.loads(a.report.read_bytes(), object_pairs_hook=pairs,
                        parse_constant=lambda v: (_ for _ in ()).throw(ValueError(v)))
    result = verify(report, a.run, a.prepared, a.artifacts)
    with a.out.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(result['status'])
