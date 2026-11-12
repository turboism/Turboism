#!/usr/bin/env python3
"""Synthetic regression for DRAFT declaration diagnostics and retained evidence expansion."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from materialize_draft_contracts import (
    OVERRIDES_FORMAT,
    load_evidence,
    render,
)
from scripts.test.reviewed_releases_fixture import table


class DraftDeclarationDiagnosticTest(unittest.TestCase):
    def test_missing_alias_names_the_required_declaration(self):
        for family, record_family in [('editor-model-read', 'editor-model'), ('ui-test', 'ui-test')]:
            with self.subTest(family=family), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                version, alias = '9.9.99', 'cubism.fixture.new-selector'

                def put(relative, value):
                    path = root / relative
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_text(json.dumps(value), encoding='utf-8')

                put('compatibility/cubism/reviewed-releases.json', json.loads(table([version])))
                put(f'build/generated/verification/cubism-{version}-{record_family}.json',
                    {'selectors': [{'alias': alias}]})
                declaration = f'compatibility/cubism/draft-src/{family}.json'
                put(declaration, {'family': family, 'releases': [version], 'entries': {}})
                with self.assertRaises(ValueError) as failure:
                    render(root)
                message = str(failure.exception)
                self.assertIn(f'cubism-{version}-{family}.json', message)
                self.assertIn(alias, message)
                self.assertIn(f'version {version}', message)
                self.assertIn(f'add entries.{alias} with a template index', message)
                self.assertIn(str(Path(declaration)), message)


class RetainedEvidenceExpansionTest(unittest.TestCase):
    """Retained evidence must expand to the exact per-version pack bytes."""

    FAMILY = 'ui-test'
    VERSION = '9.9.99'
    RECORD_NAME = f'cubism-{VERSION}-{FAMILY}.json'
    ARTIFACT_SHA256 = 'a' * 64

    def _root(self, directory: str) -> Path:
        root = Path(directory)
        baseline = {
            'format': 'turboism.mapping.pack',
            'schemaVersion': 1,
            'status': 'DRAFT',
            'cubismVersion': '${version}',
            'entries': [{'profile': 'cubism-${version}', 'runtime': 'fixture/Old'}],
            'metadata': {'artifactSha256': '${artifactSha256}', 'selectorCount': '${selectorCount}'},
        }
        baseline_path = root / 'compatibility/cubism/mapping-packs/draft/baseline' / f'{self.FAMILY}.json'
        baseline_path.parent.mkdir(parents=True, exist_ok=True)
        baseline_path.write_text(json.dumps(baseline, indent=2) + '\n', encoding='utf-8')
        overrides = {
            'format': OVERRIDES_FORMAT,
            'schemaVersion': 1,
            'family': self.FAMILY,
            'baselineVersion': self.VERSION,
            'overrides': {},
        }
        overrides_path = root / 'compatibility/cubism/mapping-packs/draft/overrides' / f'{self.FAMILY}.json'
        overrides_path.parent.mkdir(parents=True, exist_ok=True)
        overrides_path.write_text(json.dumps(overrides, indent=2) + '\n', encoding='utf-8')
        records = {
            self.RECORD_NAME: {
                'artifact': {'name': 'Live2D_Cubism.jar', 'size': 1, 'sha256': self.ARTIFACT_SHA256},
                'profileId': f'cubism-{self.VERSION}',
                'verificationId': f'cubism-{self.VERSION}.{self.FAMILY}.static',
                'selectors': [{'alias': 'fixture.selector'}],
                'capabilityIds': ['fixture.capability'],
            },
        }
        return root, records

    def test_baseline_tokens_expand_to_record_derived_literals(self):
        with tempfile.TemporaryDirectory() as directory:
            root, records = self._root(directory)
            evidence = load_evidence(root, records)
            pack = json.loads(evidence[self.FAMILY][self.VERSION])
            self.assertEqual(self.VERSION, pack['cubismVersion'])
            self.assertEqual(f'cubism-{self.VERSION}', pack['entries'][0]['profile'])
            self.assertEqual(self.ARTIFACT_SHA256, pack['metadata']['artifactSha256'])
            self.assertEqual(1, pack['metadata']['selectorCount'])

    def test_override_patches_rebuild_every_declared_version(self):
        with tempfile.TemporaryDirectory() as directory:
            root, records = self._root(directory)
            newer = '9.9.100'
            records[f'cubism-{newer}-{self.FAMILY}.json'] = dict(
                records[self.RECORD_NAME], profileId=f'cubism-{newer}')
            overrides_path = root / 'compatibility/cubism/mapping-packs/draft/overrides' / f'{self.FAMILY}.json'
            overrides = json.loads(overrides_path.read_text(encoding='utf-8'))
            overrides['overrides'] = {newer: [{'op': 'replace', 'path': '/entries/0/runtime', 'value': 'fixture/New'}]}
            overrides_path.write_text(json.dumps(overrides, indent=2) + '\n', encoding='utf-8')

            evidence = load_evidence(root, records)
            self.assertEqual('fixture/Old', json.loads(evidence[self.FAMILY][self.VERSION])['entries'][0]['runtime'])
            self.assertEqual('fixture/New', json.loads(evidence[self.FAMILY][newer])['entries'][0]['runtime'])


if __name__ == '__main__':
    unittest.main()
