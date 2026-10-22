#!/usr/bin/env python3
"""Generate DRAFT packs/profiles; observation packs remain source evidence.

Run after materialize_verification_records.py (no Cubism jars are needed).
--check-against ROOT compares every migrated file with a pre-migration tree.
Historical DRAFT annotations and presentation live in draft-src; selector facts,
artifact identities, counts and hashes always come from materialized records.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

from materialize_verification_records import read_json, select, write_records
from preview.reviewed_host_artifacts import load_releases

ROOT = Path(__file__).resolve().parents[1]
SOURCES = Path('compatibility/cubism/draft-src')
PACKS = Path('compatibility/cubism/mapping-packs/draft')
PROFILES = Path('compatibility/cubism/profiles/draft')
OUTPUT = Path('build/generated/draft')


def substitute(value, values: dict):
    if isinstance(value, str):
        if value in values:
            return values[value]
        for key, replacement in values.items():
            if isinstance(replacement, str):
                value = value.replace(key, replacement)
        return value
    if isinstance(value, list):
        return [substitute(item, values) for item in value]
    if isinstance(value, dict):
        return {key: substitute(item, values) for key, item in value.items()}
    return value


def encode(document: dict) -> bytes:
    return (json.dumps(document, indent=2) + '\n').encode('utf-8')


def render(root: Path) -> tuple[dict[str, bytes], dict[str, bytes]]:
    versions = [r['version'] for r in load_releases(root / 'compatibility/cubism/reviewed-releases.json')]
    record_dir = root / 'build/generated/verification'
    records = {p.name: (p.read_bytes(), json.loads(p.read_bytes())) for p in record_dir.glob('*.json')}
    if not records:
        raise ValueError('run materializeVerificationRecords before materializing DRAFT contracts')
    packs = {p.name: p.read_bytes() for p in (root / PACKS).glob('*.json')}
    for path in sorted((root / SOURCES).glob('*.json')):
        if path.stem == 'profiles':
            continue
        source = read_json(path)
        family = source['family']
        if family != path.stem or not set(source['releases']) <= set(versions):
            raise ValueError(f'{path}: invalid family/release declaration')
        for version in source['releases']:
            name = f'cubism-{version}-{family}.json'
            record_name = f'cubism-{version}-editor-model.json' if family == 'editor-model-read' else name
            raw, record = records[record_name]
            selectors = record['selectors']
            active = {alias for alias, rules in source['entries'].items()
                      if any(version in expand(rule['versions'], versions) for rule in rules)}
            if active != {s['alias'] for s in selectors}:
                raise ValueError(f'{name}: pack/record alias sets differ')
            values = {
                '${version}': version,
                '${artifactSha256}': record['artifact']['sha256'],
                '${inventoryRef}': f'compatibility/cubism/verification/{record_name}',
                '${artifactName}': record['artifact']['name'],
                '${artifactSize}': record['artifact']['size'],
                '${profileId}': record['profileId'],
                '${verificationId}': record['verificationId'],
                '${selectorCount}': len(selectors),
                '${capabilityCount}': len(record['capabilityIds']),
                '${capabilityIds}': record['capabilityIds'],
                '${verificationRecordSha256}': hashlib.sha256(raw).hexdigest(),
            }
            pack = substitute(select(source['envelopes'], version, versions), values)
            entries = []
            for selector in selectors:
                index = select(source['entries'][selector['alias']], version, versions, 'template')
                template = source['templates'][index]
                entry = dict(template['annotations'], semanticName=selector['mappingId'],
                             kind=selector['kind'], name=selector['alias'],
                             runtime=selector['ownerInternalName'] if selector['kind'] == 'class' else selector['memberName'],
                             profile=f'cubism-{version}', descriptor=selector.get('descriptor'))
                entry['x.verification'] = {'ownerInternalName': selector['ownerInternalName']}
                entries.append({key: entry[key] for key in template['keys']})
            # The historical envelope places entries before metadata.
            metadata = pack.pop('metadata', None)
            pack['entries'] = entries
            if metadata is not None:
                pack['metadata'] = metadata
            packs[name] = encode(pack)
    source = read_json(root / SOURCES / 'profiles.json')
    profiles = {}
    for version in versions:
        document = dict(source['template'])
        document['mappingPacks'] = [f'cubism-{version}-{family}' for family in source['familyOrder']
                                    if f'cubism-{version}-{"editor-model" if family == "editor-model-read" else family}.json' in records]
        document.update(source['overrides'].get(version, {}))
        document = substitute(document, {'${version}': version})
        layout = select(source['layouts'], version, versions)
        document = {key: document[key] for key in layout['keyOrder']}
        name = f'cubism-{version}.json'
        if document['profileId'] != f'cubism-{version}' or document['cubismVersion'] != version:
            raise ValueError(f'{name}: profile identity mismatch')
        if len(set(document['mappingPacks'])) != len(document['mappingPacks']):
            raise ValueError(f'{name}: duplicate pack references')
        for pack in document['mappingPacks']:
            if pack + '.json' not in packs:
                raise ValueError(f'{name}: missing mapping pack {pack}')
        raw = encode(document)
        if layout.get('inlinePackEnd'):
            raw = raw.replace(b'"cubism-5.3.03-protected-export"\n  ]', b'"cubism-5.3.03-protected-export"  ]')
        profiles[name] = raw
    return packs, profiles


def expand(spec: str, versions: list[str]) -> list[str]:
    from materialize_verification_records import expand_ranges
    return expand_ranges(spec, versions)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--check-against', type=Path, help='pre-migration repository root')
    args = parser.parse_args()
    try:
        packs, profiles = render(args.root)
        if args.check_against:
            for relative, rendered in [(PACKS, packs), (PROFILES, profiles)]:
                existing = {p.name: p.read_bytes() for p in (args.check_against / relative).glob('*.json')}
                if existing != rendered:
                    changed = sorted(name for name in set(existing) | set(rendered) if existing.get(name) != rendered.get(name))
                    raise ValueError(f'{relative}: byte differences: {changed}')
        write_records(args.root / OUTPUT / 'mapping-packs', packs)
        write_records(args.root / OUTPUT / 'profiles', profiles)
        print(f'PASS: {len(packs)} DRAFT packs and {len(profiles)} profiles' + (' byte-identical' if args.check_against else ' materialized'))
        return 0
    except (ValueError, KeyError, TypeError, OSError) as failure:
        parser.exit(1, f'FAIL: {failure}\n')


if __name__ == '__main__':
    raise SystemExit(main())
