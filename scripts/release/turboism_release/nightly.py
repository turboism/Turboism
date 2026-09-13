"""Changed-only Nightly policy and publishing; stable promotion stays separate."""
from __future__ import annotations

import hashlib
import json
import os
import re
from pathlib import Path

from .build_identity import identity, decode_file, verify_receipt
from .promotion import GitHub, ensure_tag, tag_binding
from .versions import STRICT_VERSION, SOURCE_SHA
from .candidate import _load_script

WORKFLOW = '.github/workflows/release.yml'
NIGHTLY = re.compile(r'^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)-0\.nightly\.([1-9]\d*)$')
RECEIPT = re.compile(r'<!-- turboism-build-v1 (.*?) -->', re.DOTALL)
SCRIPTS = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def receipt_from_body(body):
    from .prerelease import receipt_from_body as read_receipt
    return read_receipt(body, 'nightly')


def names_for(version):
    require(NIGHTLY.fullmatch(version), 'Not a canonical Nightly version')
    from .prerelease import names_for as primary_names
    return primary_names(version)


def missing_assets(raw, expected, tag):
    require(NIGHTLY.fullmatch(tag[1:]), 'Not a canonical Nightly tag')
    from .prerelease import missing_assets as missing
    return missing(raw, expected, tag)


def list_releases(github):
    releases = []
    for page in range(1, 101):
        batch = github.api(f'releases?per_page=100&page={page}')
        require(isinstance(batch, list), 'Invalid GitHub release list')
        releases.extend(batch)
        if len(batch) < 100:
            return releases
    raise ValueError('Release enumeration exceeded the safety limit; not treating it as no changes')


def validate_published(github, raw):
    receipt = receipt_from_body(raw.get('body'))
    require(raw.get('tag_name') == 'v'+receipt['version'], 'Nightly tag/receipt mismatch')
    expected = {a['name']: {'size': a['size'], 'sha256': str(a.get('digest','')).removeprefix('sha256:')}
                for a in raw.get('assets', [])}
    missing_assets(raw, expected, raw['tag_name'])
    stored = decode_file(github.api(f"contents/entries/{receipt['runId']}-{receipt['runAttempt']}.json?ref=build-ledger"))
    require(stored == receipt, 'Published Nightly is not bound to the build ledger')
    obj = github.api('git/ref/tags/'+raw['tag_name'])['object']
    for _ in range(4):
        if obj.get('type') != 'tag':
            break
        require(SOURCE_SHA.fullmatch(obj.get('sha','')), 'Invalid annotated tag')
        obj = github.api('git/tags/'+obj['sha'])['object']
    require(obj.get('type') == 'commit' and obj.get('sha') == receipt['sourceRevision'], 'Nightly source/tag mismatch')
    return receipt


def plan(github, source_sha, declared_version):
    require(SOURCE_SHA.fullmatch(source_sha) and STRICT_VERSION.fullmatch(declared_version), 'Invalid source/base version')
    raws = list_releases(github)
    successes = []
    bases = [tuple(map(int, declared_version.split('.')))]
    for raw in raws:
        if raw.get('draft') is True:
            continue
        tag = raw.get('tag_name','')
        if tag.startswith('v') and NIGHTLY.fullmatch(tag[1:]):
            require(raw.get('published_at'), 'Nightly publication timestamp missing')
            receipt = validate_published(github, raw)
            successes.append(receipt)
            bases.append(tuple(map(int, NIGHTLY.fullmatch(receipt['version']).groups()[:3])))
        elif raw.get('prerelease') is False and tag.startswith('v') and STRICT_VERSION.fullmatch(tag[1:]):
            major, minor, patch = map(int, tag[1:].split('.'))
            bases.append((major, minor, patch+1))
    previous = max(successes, key=lambda r:r['buildNumber']) if successes else None
    reason = 'new_source' if previous else 'first_nightly'
    should_build = previous is None or previous['sourceRevision'] != source_sha
    if not should_build:
        reason = 'already_published'
    elif previous:
        relation = github.api(f"compare/{previous['sourceRevision']}...{source_sha}")['status']
        require(relation in ('ahead','behind','identical'), 'Nightly source history diverged; refusing automatic replacement')
        if relation in ('behind','identical'):
            should_build, reason = False, 'outdated_source'
    return {'should_build':should_build,'reason':reason,'source_sha':source_sha,
            'base_version':'.'.join(map(str,max(bases))),
            'previous_source':previous['sourceRevision'] if previous else None}


def publish(github, dist, receipt, manifest, notes):
    from .prerelease import publish as publish_verified
    require(receipt['channel'] == 'nightly', 'Not a Nightly build')
    return publish_verified(github, dist, receipt, manifest, notes)
