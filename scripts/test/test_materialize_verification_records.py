#!/usr/bin/env python3
"""Small byte-preservation and fail-closed fixtures for shadow materialization."""
from __future__ import annotations

import copy
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import materialize_verification_records as gen
import version_table_releases as releases_gen


VERSIONS = ['5.0.00', '5.0.01', '5.0.02']
RELEASES = {v: {'artifact': dict(name='Live2D_Cubism.jar', size=i + 1, sha256='ab' * 32)}
            for i, v in enumerate(VERSIONS)}


def record(version: str) -> dict:
    return dict(format='turboism.static.verification.record', schemaVersion=1,
                **gen.derived(version, 'fixture', RELEASES[version]),
                adapterSliceId='adapter.fixture', capabilityIds=['fixture.read'],
                evidenceType='JAR_METADATA', owner='fixture', status='VERIFIED_STATIC',
                verifiedBy='fixture', verifiedAt='2026-10-22T00:00:00Z', safeMode='closed',
                selectors=[dict(mappingId='original', alias='fixture.member', kind='class',
                                ownerInternalName='fixture/Host', memberName=None, descriptor=None,
                                requiredAccessFlags=1, forbiddenAccessFlags=0, status='VERIFIED_STATIC')],
                capabilityConditions={'fixture.read': ['structure']})


def encoded(document: dict, indent: int = 2) -> bytes:
    return gen.encode_record(document, dict(keyOrder=list(document), indent=indent, fieldStyle={}, duplicateValues={}))


class MaterializeVerificationRecordsTest(unittest.TestCase):
    def test_import_materialize_round_trip(self):
        with tempfile.TemporaryDirectory() as temp:
            directory = Path(temp)
            for i, v in enumerate(VERSIONS):
                d = record(v)
                if i == 1:
                    d = dict(reversed(list(d.items())))
                    d['selectors'][0] = dict(reversed(list(d['selectors'][0].items())))
                layout = dict(keyOrder=list(d), indent=1 if i == 1 else 2, fieldStyle={}, duplicateValues={})
                if i == 2:
                    # Preserve the historical duplicate capabilityConditions,
                    # and compact selector rows, without an opaque raw fallback.
                    layout['keyOrder'].insert(2, 'capabilityConditions')
                    layout['duplicateValues'] = {'2': d['capabilityConditions']}
                    layout['fieldStyle']['2'] = dict(indent=2, mode='inlineArrays')
                    index = layout['keyOrder'].index('selectors')
                    layout['fieldStyle'][str(index)] = dict(indent=2, mode='rows')
                (directory / f'cubism-{v}-fixture.json').write_bytes(gen.encode_record(d, layout))
            source = gen.import_records(directory, RELEASES)['fixture']
            path = directory / 'family.json'
            gen.write_json(path, source)
            source = gen.read_json(path)
            path.unlink()  # Keep the comparison directory strictly records only.
            rendered = gen.materialize_family(source, RELEASES)
            gen.check_records(rendered, directory)
            (directory / 'cubism-5.0.00-fixture.json').write_bytes(b'changed')
            with self.assertRaisesRegex(ValueError, 'bytes differ'):
                gen.check_records(rendered, directory)

    def test_range_boundary(self):
        docs = {v: record(v) for v in VERSIONS}
        docs[VERSIONS[0]]['selectors'] = []
        source = gen.import_family('fixture', {v: encoded(d) for v, d in docs.items()}, RELEASES)
        self.assertEqual('5.0.01-5.0.02', source['selectors'][0]['versions'])
        self.assertEqual(VERSIONS[1:], gen.expand_ranges('5.0.01-5.0.02', VERSIONS))
        for bad in ('5.0.00-5.0.03', '5.0.02-5.0.01', '5.0.00,5.0.00'):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                gen.expand_ranges(bad, VERSIONS)

    def test_mapping_id_changes_are_exact_and_must_cover_ranges(self):
        docs = {v: record(v) for v in VERSIONS}
        docs[VERSIONS[1]]['selectors'][0]['mappingId'] = 'older-pack-stamp'
        raw = {v: encoded(d) for v, d in docs.items()}
        source = gen.import_family('fixture', raw, RELEASES)
        self.assertEqual([{'versions': '5.0.00,5.0.02', 'id': 'original'},
                          {'versions': '5.0.01', 'id': 'older-pack-stamp'}], source['selectors'][0]['mappingId'])
        self.assertEqual({f'cubism-{v}-fixture.json': b for v, b in raw.items()}, gen.materialize_family(source, RELEASES))
        broken = copy.deepcopy(source)
        broken['selectors'][0]['mappingId'].pop()
        with self.assertRaisesRegex(ValueError, 'coverage differs'):
            gen.materialize_family(broken, RELEASES)

    def test_unrepresentable_record_is_rejected(self):
        d = record(VERSIONS[0])
        with self.assertRaisesRegex(ValueError, 'unrepresentable'):
            gen.import_family('fixture', {VERSIONS[0]: gen.signature(d).encode()}, RELEASES)
        d['selectors'].append(copy.deepcopy(d['selectors'][0]))
        with self.assertRaisesRegex(ValueError, 'duplicate selector alias'):
            gen.import_family('fixture', {VERSIONS[0]: encoded(d)}, RELEASES)
        with self.assertRaisesRegex(ValueError, 'duplicate JSON key'):
            gen.unique_object([('key', 1), ('key', 2)])
        # Symbol spelling must not hide a changed Java version literal.
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            files = {
                releases_gen.SOURCE_RELATIVE: (
                    'public static final String CUBISM_5_0_00_VERSION = "5.0.99";\n'
                    'CUBISM_5_0_00 = new HostArtifactDigest(1L, "' + 'ab' * 32 + '");'
                ),
                releases_gen.JAVA_RELEASES: 'case ReviewedHostArtifacts.CUBISM_5_0_00_VERSION -> build == 1;',
                releases_gen.JAVA_CORE: 'case "5.0.00" -> exact(5, 0, 0);',
            }
            for name, text in files.items():
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(text, encoding='utf-8')
            with self.assertRaisesRegex(ValueError, 'version literals'):
                releases_gen.import_releases(root)


if __name__ == '__main__':
    unittest.main()
