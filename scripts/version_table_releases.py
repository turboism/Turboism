#!/usr/bin/env python3
"""Shadow import of reviewed releases. Java remains authoritative in Phase 1.

This deliberately supports only the current Java declaration shapes. A Java
refactor must update this importer (or replace it in Phase 2), never guess facts.
"""
from __future__ import annotations

import re
from pathlib import Path

from preview.reviewed_host_artifacts import SOURCE_RELATIVE, load

RELEASES = Path('compatibility/cubism/reviewed-releases.json')
JAVA_RELEASES = Path('runtime/src/main/java/dev/turboism/mapping/verification/ReviewedCubismReleases.java')
JAVA_CORE = Path('runtime/src/main/java/dev/turboism/adapter/cubism/core/CoreVersionExpectation.java')


def import_releases(root: Path) -> dict:
    artifacts = load(root / SOURCE_RELATIVE)
    declarations = (root / JAVA_RELEASES).read_text(encoding='utf-8')
    builds = {}
    for symbol, build in re.findall(r'case ReviewedHostArtifacts.CUBISM_([\d_]+)_VERSION -> build == (\d+);', declarations):
        version = symbol.replace('_', '.')
        if version in builds:
            raise ValueError(f'duplicate build: {version}')
        builds[version] = int(build)
    core = {}
    for labels, major, minor, patch in re.findall(r'case\s+((?:"[\d.]+"[\s,]*)+)\s*-> exact\((\d+), (\d+), (\d+)\);', (root / JAVA_CORE).read_text(encoding='utf-8')):
        for version in re.findall(r'"([\d.]+)"', labels):
            if version in core:
                raise ValueError(f'duplicate Core tuple: {version}')
            core[version] = dict(major=int(major), minor=int(minor), patch=int(patch))
    host = (root / SOURCE_RELATIVE).read_text(encoding='utf-8')
    if not re.search(r'boolean admitsFullRuntime\(final String cubismVersion\)\s*\{\s*Objects.requireNonNull\(cubismVersion, "cubismVersion"\);\s*return REVIEWED_CUBISM_VERSION_SET.contains\(cubismVersion\);\s*\}', host):
        raise ValueError('unsupported Java full-runtime declaration; update shadow importer')
    reviewed_set = re.search(r'REVIEWED_CUBISM_VERSION_SET = Set.of\((.*?)\);', host, re.S)
    if reviewed_set is None:
        raise ValueError('missing Java reviewed version set')
    versions = re.findall(r'CUBISM_([\d_]+)_VERSION', reviewed_set[1])
    if len(set(versions)) != len(versions) or {v.replace('_', '.') for v in versions} != set(artifacts):
        raise ValueError('Java reviewed version set and artifact declarations disagree')
    if set(builds) != set(artifacts) or set(core) != set(artifacts):
        raise ValueError('Java artifact/build/Core version sets disagree')
    return {
        'format': 'turboism.reviewed-releases/v1',
        'releases': [dict(version=v, build=builds[v],
                          artifact=dict(name='Live2D_Cubism.jar', size=a.size, sha256=a.sha256),
                          core=core[v], fullRuntime=True, hostLabel=f'Live2D Cubism {v}')
                     for v, a in artifacts.items()],
    }
