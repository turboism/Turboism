#!/usr/bin/env python3
"""Tiny synthetic regression for actionable DRAFT declaration diagnostics."""
from __future__ import annotations

import json
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from materialize_draft_contracts import render
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
                self.assertIn(declaration, message)


if __name__ == '__main__':
    unittest.main()
