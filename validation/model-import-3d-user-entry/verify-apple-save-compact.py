#!/usr/bin/env python3
"""Explicit 9x9 layer storage gate; all 17x17 Warp controls remain mandatory."""
import argparse
import json
import runpy
from pathlib import Path

SAVE = runpy.run_path(str(Path(__file__).with_name('verify-apple-save.py')))


def verify(r, run, prepared, artifacts):
    SAVE['require'](r['schemaVersion'] == 'mi3d-apple-menu-save-compact-v1', 'explicit compact schema')
    saved = SAVE['verify_save'](dict(r, schemaVersion='mi3d-apple-menu-save-v1'), artifacts)
    base = dict(r, schemaVersion='mi3d-apple-user-entry-v1', scope='REAL_MENU_IMPORT_UNDO_REDO_ONLY')
    content = SAVE['BASE']['verify'](base, run, prepared, artifacts, SAVE['BASE']['KEYS'][::2])
    return dict(status='APPLE_COMPACT_MENU_SAVE_CONTENT_CHECKED_NOT_PROCESS_OR_REOPEN_ACCEPTANCE',
                content=content, saved=saved, persistenceAccepted=False,
                officialExportAccepted=False, goalComplete=False)


if __name__ == '__main__':
    p = argparse.ArgumentParser()
    for name in ('report', 'prepared', 'artifacts', 'out'):
        p.add_argument('--' + name, type=Path, required=True)
    p.add_argument('--run', required=True)
    a = p.parse_args()
    SAVE['require'](not a.report.is_symlink() and a.report.stat().st_size <= 64 * 1024 * 1024, 'bounded report')
    def pairs(items):
        result = {}
        for key, value in items:
            SAVE['require'](key not in result, 'duplicate JSON key')
            result[key] = value
        return result
    report = json.loads(a.report.read_bytes(), object_pairs_hook=pairs,
                        parse_constant=lambda v: (_ for _ in ()).throw(ValueError(v)))
    result = verify(report, a.run, a.prepared, a.artifacts)
    with a.out.open('x') as stream:
        json.dump(result, stream, indent=2)
        stream.write('\n')
    print(result['status'])
