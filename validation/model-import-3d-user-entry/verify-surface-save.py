#!/usr/bin/env python3
"""Independent surface menu/save content gate; process, reopen and export remain separate."""
import argparse
import hashlib
import json
import runpy
from pathlib import Path

MAX_BYTES = 64 * 1024 * 1024


def require(ok, why):
    if not ok:
        raise ValueError('SURFACE_SAVE: ' + why)


def verify_save(r, artifacts):
    require(r['schemaVersion'] == 'mi3d-surface-menu-v1'
            and r['scope'] == 'REAL_MENU_IMPORT_UNDO_REDO_SOURCE_RENDER'
            and r['saveReopen'] == 'SAVED_REOPEN_NOT_RUN', 'explicit save scope')
    s = r['persistenceSave']
    require(s['commandStatus'] == 'EXECUTED' and s['maximumBytes'] == MAX_BYTES,
            'typed command and bounded size policy')
    require(s['before'] == s['after'] == r['imported'], 'all authored state preserved')
    require(s['historyUnchanged'] is True and s['historyBefore'] == s['historyAfter']
            == r['sourceTextureRender']['restoredHistory'], 'full history preserved')
    require(s['artifact'] == 'persistence/mi3d-surface-model.cmo3', 'fixed target')
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

