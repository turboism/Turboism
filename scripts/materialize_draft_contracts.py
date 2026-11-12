#!/usr/bin/env python3
"""Generate DRAFT packs and profiles from retained observation evidence.

Run after materialize_verification_records.py (no Cubism jars are needed).
Retained evidence lives in mapping-packs/draft/baseline plus per-version override
tables; this script expands both, then projects draft-src annotations. Historical
DRAFT annotations live in draft-src; generated objects use sorted keys. Selector
facts, artifact identities, counts and hashes always come from materialized records.
--check-against ROOT compares all packs/profiles with a reference tree.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from materialize_verification_records import read_json, select, write_records
from preview.reviewed_host_artifacts import load_releases

ROOT = Path(__file__).resolve().parents[1]
SOURCES = Path('compatibility/cubism/draft-src')
PACKS = Path('compatibility/cubism/mapping-packs/draft')
BASELINES = PACKS / 'baseline'
OVERRIDES = PACKS / 'overrides'
PROFILES = Path('compatibility/cubism/profiles/draft')
OUTPUT = Path('build/generated/draft')

BASELINE_FORMAT = 'turboism.mapping.pack.baseline'
OVERRIDES_FORMAT = 'turboism.mapping.pack.overrides'
EVIDENCE_SCHEMA_VERSION = 1
EDITOR_MODEL_FAMILY = 'editor-model-read'
EDITOR_MODEL_RECORD_FAMILY = 'editor-model'

# Retained evidence restates these values verbatim from the materialized records.
# Tokens keep one baseline valid for every version of its family.
RECORD_TOKENS = {
    '${artifactSha256}': lambda record: record['artifact']['sha256'],
    '${artifactName}': lambda record: record['artifact']['name'],
    '${artifactSize}': lambda record: record['artifact']['size'],
    '${profileId}': lambda record: record['profileId'],
    '${verificationId}': lambda record: record['verificationId'],
    '${selectorCount}': lambda record: len(record['selectors']),
    '${capabilityCount}': lambda record: len(record['capabilityIds']),
}


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
    return (json.dumps(document, indent=2, sort_keys=True) + '\n').encode('utf-8')


def encode_evidence(document: dict) -> bytes:
    """Serialize retained evidence in its stored key order, not sorted."""
    return (json.dumps(document, indent=2) + '\n').encode('utf-8')


def record_name(family: str, version: str) -> str:
    suffix = EDITOR_MODEL_RECORD_FAMILY if family == EDITOR_MODEL_FAMILY else family
    return f'cubism-{version}-{suffix}.json'


def record_of(entry):
    """Accept either a decoded record or a (raw bytes, record) pair."""
    return entry[1] if isinstance(entry, tuple) else entry


def evidence_values(record: dict, version: str) -> dict:
    """Values that retained evidence restates from its verification record."""
    values = {'${version}': version}
    for token, read in RECORD_TOKENS.items():
        values[token] = read(record)
    return values


def expand_text(text: str, values: dict) -> str:
    """Replace tokens in raw evidence text; typed tokens lose their JSON quotes."""
    for token, replacement in values.items():
        if isinstance(replacement, str):
            text = text.replace(json.dumps(token), json.dumps(replacement))
    for token, replacement in values.items():
        if isinstance(replacement, str):
            continue
        text = text.replace(json.dumps(token), json.dumps(replacement))
    for token, replacement in values.items():
        if isinstance(replacement, str):
            text = text.replace(token, replacement)
    return text


def apply_patches(document, patches: list):
    """Apply ordered JSON-pointer replacements to a decoded evidence document."""
    for patch in patches:
        path = patch['path']
        if path == '/':
            document = patch['value']
            continue
        parts = path.split('/')[1:]
        node = document
        for part in parts[:-1]:
            node = node[int(part)] if isinstance(node, list) else node[part]
        last = parts[-1]
        if isinstance(node, list):
            node[int(last)] = patch['value']
        else:
            node[last] = patch['value']
    return document


def load_evidence(root: Path, records: dict) -> dict[str, dict[str, bytes]]:
    """Expand every retained baseline and override table into per-version pack bytes."""
    baselines = {path.stem: path.read_bytes() for path in (root / BASELINES).glob('*.json')}
    tables = {path.stem: read_json(path) for path in (root / OVERRIDES).glob('*.json')}
    if set(baselines) != set(tables):
        raise ValueError(
            f'retained evidence families disagree: baseline-only {sorted(set(baselines) - set(tables))}, '
            f'overrides-only {sorted(set(tables) - set(baselines))}'
        )
    expanded: dict[str, dict[str, bytes]] = {}
    for family, baseline in sorted(baselines.items()):
        table = tables[family]
        if table.get('format') != OVERRIDES_FORMAT or table.get('schemaVersion') != EVIDENCE_SCHEMA_VERSION:
            raise ValueError(f'{OVERRIDES / family}.json: unexpected format or schemaVersion')
        if table.get('family') != family:
            raise ValueError(f'{OVERRIDES / family}.json: family mismatch')
        versions = {}
        for version in [table['baselineVersion'], *sorted(table['overrides'])]:
            name = record_name(family, version)
            values = evidence_values(record_of(records[name]), version) if name in records else {'${version}': version}
            text = expand_text(baseline.decode('utf-8'), values)
            patches = table['overrides'].get(version)
            versions[version] = encode_evidence(apply_patches(json.loads(text), patches)) if patches else text.encode('utf-8')
        expanded[family] = versions
    return expanded


def load_records(root: Path) -> dict:
    record_dir = root / 'build/generated/verification'
    records = {p.name: (p.read_bytes(), json.loads(p.read_bytes())) for p in record_dir.glob('*.json')}
    if not records:
        raise ValueError('run materializeVerificationRecords before materializing DRAFT contracts')
    return records


def render(root: Path) -> tuple[dict[str, bytes], dict[str, bytes]]:
    versions = [r['version'] for r in load_releases(root / 'compatibility/cubism/reviewed-releases.json')]
    records = load_records(root)
    evidence = load_evidence(root, records)
    packs = {f'cubism-{version}-{family}.json': raw
             for family, versions in evidence.items() for version, raw in versions.items()}
    for path in sorted((root / SOURCES).glob('*.json')):
        if path.stem == 'profiles':
            continue
        source = read_json(path)
        family = source['family']
        if family != path.stem or not set(source['releases']) <= set(versions):
            raise ValueError(f'{path}: invalid family/release declaration')
        for version in source['releases']:
            name = f'cubism-{version}-{family}.json'
            record_name_ = record_name(family, version)
            raw, record = records[record_name_]
            selectors = record['selectors']
            active = {alias for alias, rules in source['entries'].items()
                      if any(version in expand(rule['versions'], versions) for rule in rules)}
            aliases = {s['alias'] for s in selectors}
            if aliases - active:
                alias = sorted(aliases - active)[0]
                raise ValueError(
                    f"{name} (version {version}): alias {alias!r} has no active DRAFT declaration; "
                    f"add entries.{alias} with a template index and versions covering {version} "
                    f"in {SOURCES / path.name}"
                )
            if active - aliases:
                raise ValueError(
                    f"{name} (version {version}): DRAFT aliases absent from record: "
                    f"{sorted(active - aliases)}; correct their version ranges in {SOURCES / path.name}"
                )
            values = {
                '${version}': version,
                '${artifactSha256}': record['artifact']['sha256'],
                '${inventoryRef}': f'compatibility/cubism/verification/{record_name_}',
                '${artifactName}': record['artifact']['name'],
                '${artifactSize}': record['artifact']['size'],
                '${profileId}': record['profileId'],
                '${verificationId}': record['verificationId'],
                '${selectorCount}': len(selectors),
                '${capabilityCount}': len(record['capabilityIds']),
                '${capabilityIds}': record['capabilityIds'],
            }
            pack = substitute(select(source['envelopes'], version, versions), values)
            entries = []
            for selector in selectors:
                index = select(source['entries'][selector['alias']], version, versions, 'template')
                template = source['templates'][index]
                entry = dict(template['annotations'], semanticName=selector['mappingId'],
                             kind=selector['kind'], name=selector['alias'],
                             runtime=selector['ownerInternalName'] if selector['kind'] == 'class' else selector['memberName'],
                             profile=f'cubism-{version}')
                if template['includeDescriptor']:
                    entry['descriptor'] = selector.get('descriptor')
                entry['x.verification'] = {'ownerInternalName': selector['ownerInternalName']}
                entries.append(entry)
            pack['entries'] = entries
            packs[name] = encode(pack)
    source = read_json(root / SOURCES / 'profiles.json')
    profiles = {}
    for version in versions:
        document = dict(source['template'])
        document['mappingPacks'] = [f'cubism-{version}-{family}' for family in source['familyOrder']
                                    if f'cubism-{version}-{"editor-model" if family == "editor-model-read" else family}.json' in records]
        document.update(source['overrides'].get(version, {}))
        document = substitute(document, {'${version}': version})
        name = f'cubism-{version}.json'
        if document['profileId'] != f'cubism-{version}' or document['cubismVersion'] != version:
            raise ValueError(f'{name}: profile identity mismatch')
        if len(set(document['mappingPacks'])) != len(document['mappingPacks']):
            raise ValueError(f'{name}: duplicate pack references')
        for pack in document['mappingPacks']:
            if pack + '.json' not in packs:
                raise ValueError(f'{name}: missing mapping pack {pack}')
        profiles[name] = encode(document)
    return packs, profiles


def expand(spec: str, versions: list[str]) -> list[str]:
    from materialize_verification_records import expand_ranges
    return expand_ranges(spec, versions)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--check-against', type=Path, help='reference repository root')
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
