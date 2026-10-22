#!/usr/bin/env python3
"""Import and materialize shadow verification families, without runtime consumers.

  python3 scripts/materialize_verification_records.py import
  python3 scripts/materialize_verification_records.py --check-against compatibility/cubism/verification
  python3 scripts/materialize_verification_records.py --output build/generated/verification

Ranges are inclusive positions in the exact reviewed release list, not semantic
version inference. Selector table indices preserve original list order; field
layouts preserve JSON key order and mixed indentation. Import refuses any input
it cannot reproduce byte for byte. There is no raw-record fallback.
"""
from __future__ import annotations

import argparse
import copy
import json
import re
from collections import Counter, defaultdict
from pathlib import Path

from version_table_releases import RELEASES, import_releases

ROOT = Path(__file__).resolve().parents[1]
RECORDS = Path('compatibility/cubism/verification')
SOURCES = Path('compatibility/cubism/verification-src')
DERIVED = {'verificationId', 'cubismVersion', 'profileId', 'evidencePath', 'artifact'}
SPECIAL = DERIVED | {'selectors', 'verifiedAt'}


def unique_object(pairs: list) -> dict:
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f'duplicate JSON key: {key}')
        result[key] = value
    return result


def read_json(path: Path) -> dict:
    return json.loads(path.read_bytes(), object_pairs_hook=unique_object)


def signature(value) -> str:
    """Include nested key order: equal JSON objects need not serialize equally."""
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


def write_json(path: Path, value) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def expand_ranges(spec: str, versions: list[str]) -> list[str]:
    result = []
    for span in spec.split(','):
        ends = span.split('-')
        if len(ends) not in (1, 2) or any(v not in versions for v in ends):
            raise ValueError(f'unknown range endpoint: {span}')
        start, stop = versions.index(ends[0]), versions.index(ends[-1])
        if start > stop:
            raise ValueError(f'reversed range: {span}')
        result.extend(versions[start:stop + 1])
    if len(set(result)) != len(result):
        raise ValueError(f'overlapping ranges: {spec}')
    return result


def ranges(selected: list[str], versions: list[str]) -> str:
    indices = sorted(versions.index(v) for v in selected)
    groups = []
    for index in indices:
        if groups and index == groups[-1][-1] + 1:
            groups[-1].append(index)
        else:
            groups.append([index])
    return ','.join(versions[g[0]] if len(g) == 1 else f'{versions[g[0]]}-{versions[g[-1]]}' for g in groups)


def fold_values(values: dict, versions: list[str], value_key: str = 'value') -> list[dict]:
    groups = {}
    for version, value in values.items():
        key = signature(value)
        if key not in groups:
            groups[key] = [value, []]
        groups[key][1].append(version)
    return [{'versions': ranges(vs, versions), value_key: value} for value, vs in groups.values()]


def select(entries: list[dict], version: str, versions: list[str], key: str = 'value'):
    matches = [e[key] for e in entries if version in expand_ranges(e['versions'], versions)]
    if len(matches) != 1:
        raise ValueError(f'{version}: expected exactly one {key}, got {len(matches)}')
    return copy.deepcopy(matches[0])


def field_text(key: str, value, style) -> str:
    if isinstance(style, int):
        return '\n'.join(json.dumps({key: value}, ensure_ascii=False, indent=style).splitlines()[1:-1])
    indent, mode = style['indent'], style['mode']
    prefix = ' ' * indent + json.dumps(key) + ': '
    if mode == 'inline':
        separators = (', ', ': ') if style['spaced'] else (',', ':')
        return prefix + json.dumps(value, ensure_ascii=False, separators=separators)
    if mode == 'rows' and isinstance(value, list):
        return prefix + '[\n' + ',\n'.join(' ' * (2 * indent) + signature(v) for v in value) + '\n' + ' ' * indent + ']'
    if mode == 'inlineArrays' and isinstance(value, dict) and all(isinstance(v, list) for v in value.values()):
        rows = [' ' * (2 * indent) + json.dumps(k) + ': ' + json.dumps(v, ensure_ascii=False) for k, v in value.items()]
        return prefix + '{\n' + ',\n'.join(rows) + '\n' + ' ' * indent + '}'
    raise ValueError('unsupported field style')


def layout_of(document: dict, raw: bytes) -> dict:
    text = raw.decode('utf-8')
    starts = [m for m in re.finditer(r'^ {1,2}"([^"\n]+)":', text, re.M) if m[1] in document]
    keys = [m[1] for m in starts]
    if set(keys) != set(document):
        raise ValueError('unrepresentable top-level layout')
    duplicates = {k for k, n in Counter(keys).items() if n > 1}
    if duplicates - {'capabilityConditions'}:
        raise ValueError('unrepresentable duplicate top-level key')
    styles, duplicate_values = {}, {}
    candidates = [1, 2] + [
        dict(indent=n, mode=mode, **extra)
        for n in (1, 2)
        for mode, extra in [('inline', {'spaced': True}), ('inline', {'spaced': False}),
                            ('rows', {}), ('inlineArrays', {})]
    ]
    for index, match in enumerate(starts):
        stop = starts[index + 1].start() if index + 1 < len(starts) else len(text) - 2
        segment = text[match.start():stop].removesuffix('\n')
        if index + 1 < len(starts):
            segment = segment.removesuffix(',')
        value = json.loads('{' + segment + '}', object_pairs_hook=unique_object)[match[1]]
        if match[1] in keys[index + 1:]:
            duplicate_values[str(index)] = value
        elif signature(value) != signature(document[match[1]]):
            raise ValueError('unrepresentable field value')
        choices = []
        for style in candidates:
            try:
                if segment == field_text(match[1], value, style):
                    choices.append(style)
            except ValueError:
                pass
        if not choices:
            raise ValueError(f'unrepresentable field layout: {match[1]}')
        styles[str(index)] = choices[0]
    default = Counter(s for s in styles.values() if isinstance(s, int)).most_common(1)[0][0]
    return {'keyOrder': keys, 'artifactKeyOrder': list(document['artifact']), 'indent': default,
            'fieldStyle': {k: s for k, s in styles.items() if s != default},
            'duplicateValues': duplicate_values}


def encode_record(document: dict, layout: dict) -> bytes:
    if set(layout['keyOrder']) != set(document):
        raise ValueError('layout keys disagree with record')
    if layout['indent'] not in (1, 2):
        raise ValueError('unsupported indentation')
    if 'artifactKeyOrder' in layout:
        document = dict(document, artifact={k: document['artifact'][k] for k in layout['artifactKeyOrder']})
    fields = [field_text(k, layout.get('duplicateValues', {}).get(str(i), document[k]),
                         layout.get('fieldStyle', {}).get(str(i), layout['indent']))
              for i, k in enumerate(layout['keyOrder'])]
    return ('{\n' + ',\n'.join(fields) + '\n}\n').encode('utf-8')


def derived(version: str, family: str, release: dict) -> dict:
    name = f'cubism-{version}-{family}.json'
    return dict(verificationId=f'cubism-{version}.{family}.static', cubismVersion=version,
                profileId=f'cubism-{version}', evidencePath=f'{RECORDS.as_posix()}/{name}',
                artifact=copy.deepcopy(release['artifact']))


def import_family(family: str, inputs: dict[str, bytes], releases: dict) -> dict:
    versions = list(releases)
    # Historical duplicate top-level capabilityConditions are represented in
    # layouts; nested duplicate keys are still rejected when parsing each field.
    documents = {v: json.loads(b) for v, b in inputs.items()}
    layouts = {}
    for version, document in documents.items():
        if version not in releases:
            raise ValueError(f'unreviewed record version: {version}')
        if document['format'] != 'turboism.static.verification.record' or document['schemaVersion'] != 1:
            raise ValueError('unsupported record format')
        for key, value in derived(version, family, releases[version]).items():
            if key not in {'artifact', 'verificationId'} and document[key] != value:
                raise ValueError(f'{version}: nonstandard {key}')
        aliases = [s['alias'] for s in document['selectors']]
        if len(set(aliases)) != len(aliases):
            raise ValueError(f'{version}: duplicate selector alias')
        layouts[version] = layout_of(document, inputs[version])
        if encode_record(document, layouts[version]) != inputs[version]:
            raise ValueError(f'{version}: unrepresentable record serialization')
    keys = set(next(iter(documents.values())))
    if any(set(d) != keys for d in documents.values()):
        raise ValueError('unrepresentable changing top-level field set')
    base, overrides = {}, []
    for key in next(iter(documents.values())):
        if key in SPECIAL:
            continue
        entries = fold_values({v: d[key] for v, d in documents.items()}, versions)
        most = max(entries, key=lambda e: len(expand_ranges(e['versions'], versions)))
        base[key] = most['value']
        overrides.extend(dict(field=key, **e) for e in entries if signature(e['value']) != signature(base[key]))
    # Editor artifacts derive from Java's shadow table. Core jar identities are
    # independent observed facts and stay explicit, never inferred from Editor.
    artifacts = {v: d['artifact'] for v, d in documents.items() if d['artifact'] != releases[v]['artifact']}
    if artifacts and (family != 'core-model-read' or any(a['name'] != 'Live2DCubismCore.jar' for a in artifacts.values())):
        raise ValueError('record artifact differs from authoritative Java Editor identity')
    table, indices, orders, mappings, applicable = [], {}, {}, defaultdict(dict), defaultdict(list)
    for version, document in documents.items():
        order = []
        for selector in document['selectors']:
            value = {k: val for k, val in selector.items() if k != 'mappingId'}
            entry = dict(base=value, keyOrder=list(selector))
            key = signature(entry)
            if key not in indices:
                indices[key] = len(table)
                table.append(entry)
            index = indices[key]
            order.append(index)
            applicable[index].append(version)
            mapping_id = selector['mappingId']
            if not isinstance(mapping_id, str) or not mapping_id:
                raise ValueError('empty mappingId')
            mappings[index][version] = mapping_id
        orders[version] = order
    for index, entry in enumerate(table):
        entry['versions'] = ranges(applicable[index], versions)
        entry['mappingId'] = fold_values(mappings[index], versions, 'id')
    source = dict(format='turboism.verification-family/v1', family=family, base=base,
                  fieldRanges=overrides, artifactRanges=fold_values(artifacts, versions),
                  selectors=table, selectorOrder=fold_values(orders, versions),
                  layouts=fold_values(layouts, versions),
                  releases={v: {'verifiedAt': d['verifiedAt']} for v, d in documents.items()})
    for version, document in documents.items():
        normal_id = derived(version, family, releases[version])['verificationId']
        if document['verificationId'] != normal_id:
            source['releases'][version]['overrides'] = {'verificationId': document['verificationId']}
    # Prove before writing a source; unsupported data must never be silently lost.
    rendered = materialize_family(source, releases)
    if rendered != {f'cubism-{v}-{family}.json': b for v, b in inputs.items()}:
        raise ValueError(f'{family}: unrepresentable import (byte mismatch)')
    return source


def materialize_family(source: dict, releases: dict) -> dict[str, bytes]:
    if source['format'] != 'turboism.verification-family/v1':
        raise ValueError('unsupported family format')
    versions = list(releases)
    family = source['family']
    if not re.fullmatch(r'[a-z][a-z0-9-]*', family):
        raise ValueError('invalid family name')
    result = {}
    declared = set(source['releases'])
    if not declared or not declared <= set(versions):
        raise ValueError(f'{family}: empty or unreviewed releases')
    for entry in source['selectors']:
        applies = set(expand_ranges(entry['versions'], versions))
        mapped = [v for e in entry['mappingId'] for v in expand_ranges(e['versions'], versions)]
        if applies != set(mapped) or len(mapped) != len(set(mapped)) or not applies <= declared:
            raise ValueError(f'{family}: selector/mappingId range coverage differs')
    for entries in [source['fieldRanges'], source['artifactRanges'], source['selectorOrder'], source['layouts']]:
        for entry in entries:
            if not set(expand_ranges(entry['versions'], versions)) <= declared:
                raise ValueError(f'{family}: range outside declared releases')
    for version in versions:
        if version not in declared:
            continue
        document = copy.deepcopy(source['base'])
        document.update(derived(version, family, releases[version]))
        seen = set()
        for entry in source['fieldRanges']:
            if version in expand_ranges(entry['versions'], versions):
                field = entry['field']
                if field in seen or field not in source['base']:
                    raise ValueError(f'{family}: invalid or overlapping field override: {field}')
                seen.add(field)
                document[field] = copy.deepcopy(entry['value'])
        if any(version in expand_ranges(e['versions'], versions) for e in source['artifactRanges']):
            document['artifact'] = select(source['artifactRanges'], version, versions)
        release = source['releases'][version]
        if set(release) - {'verifiedAt', 'overrides'} or set(release.get('overrides', {})) - {'verificationId'}:
            raise ValueError('unsupported per-version exception')
        document.update(release.get('overrides', {}))
        document['verifiedAt'] = release['verifiedAt']
        active = {i for i, s in enumerate(source['selectors']) if version in expand_ranges(s['versions'], versions)}
        order = select(source['selectorOrder'], version, versions)
        if len(order) != len(set(order)) or set(order) != active:
            raise ValueError(f'{family} {version}: selector order differs from active set')
        selectors = []
        for index in order:
            entry = source['selectors'][index]
            selector = dict(entry['base'], mappingId=select(entry['mappingId'], version, versions, 'id'))
            if set(entry['keyOrder']) != set(selector) or len(entry['keyOrder']) != len(selector):
                raise ValueError('selector key order differs from selector fields')
            selectors.append({k: selector[k] for k in entry['keyOrder']})
        if len({s['alias'] for s in selectors}) != len(selectors):
            raise ValueError('duplicate active selector alias')
        document['selectors'] = selectors
        result[f'cubism-{version}-{family}.json'] = encode_record(document, select(source['layouts'], version, versions))
    return result


def import_records(directory: Path, releases: dict) -> dict[str, dict]:
    families = defaultdict(dict)
    for path in sorted(directory.glob('*.json')):
        match = re.fullmatch(r'cubism-([\d.]+)-([a-z][a-z0-9-]*)\.json', path.name)
        if not match:
            raise ValueError(f'invalid record filename: {path.name}')
        version, family = match.groups()
        families[family][version] = path.read_bytes()
    if not families:
        raise ValueError('empty record directory')
    return {family: import_family(family, records, releases) for family, records in sorted(families.items())}


def check_records(rendered: dict[str, bytes], directory: Path) -> None:
    existing = {p.name: p for p in directory.glob('*.json')}
    if set(existing) != set(rendered):
        raise ValueError(f'record file set differs: missing={sorted(set(existing) - set(rendered))}, extra={sorted(set(rendered) - set(existing))}')
    changed = [name for name, raw in rendered.items() if raw != existing[name].read_bytes()]
    if changed:
        raise ValueError(f'record bytes differ: {changed}')


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', nargs='?', choices=['import', 'materialize'], default='materialize')
    parser.add_argument('--root', type=Path, default=ROOT)
    parser.add_argument('--check-against', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        expected = import_releases(root)
        if args.command == 'import':
            sources = import_records(root / RECORDS, {r['version']: r for r in expected['releases']})
            # Refuse stale files rather than silently deleting authoring input.
            existing = {p.stem for p in (root / SOURCES).glob('*.json')}
            if existing - set(sources):
                raise ValueError('unexpected family sources; refusing import')
            write_json(root / RELEASES, expected)
            for family, source in sources.items():
                write_json(root / SOURCES / f'{family}.json', source)
        actual = read_json(root / RELEASES)
        if json.dumps(actual, sort_keys=True) != json.dumps(expected, sort_keys=True):
            raise ValueError('reviewed-releases.json differs from authoritative Java declarations')
        releases = {r['version']: r for r in actual['releases']}
        rendered = {}
        paths = sorted((root / SOURCES).glob('*.json'))
        if not paths:
            raise ValueError('empty family source directory')
        for path in paths:
            source = read_json(path)
            if source['family'] != path.stem:
                raise ValueError(f'family filename mismatch: {path}')
            records = materialize_family(source, releases)
            if set(records) & set(rendered):
                raise ValueError('duplicate materialized filename')
            rendered.update(records)
        if args.check_against:
            check_records(rendered, root / args.check_against)
        output_path = args.output
        if output_path is None and not args.check_against and args.command == 'materialize':
            output_path = Path('build/generated/verification')
        if output_path:
            output = root / output_path
            if output.resolve() == (root / RECORDS).resolve():
                raise ValueError('shadow mode cannot overwrite committed records')
            unexpected = {p.name for p in output.glob('*.json')} - set(rendered)
            if unexpected:
                raise ValueError(f'unexpected output records; refusing to leave stale files: {sorted(unexpected)}')
            for name, raw in sorted(rendered.items()):
                output.mkdir(parents=True, exist_ok=True)
                (output / name).write_bytes(raw)
        print(f'PASS: Java release facts agree; materialized {len(rendered)} records from {len(paths)} families' + (' byte-for-byte' if args.check_against else ''))
        return 0
    except (ValueError, KeyError, TypeError, OSError) as failure:
        parser.exit(1, f'FAIL: {failure}\n')


if __name__ == '__main__':
    raise SystemExit(main())
