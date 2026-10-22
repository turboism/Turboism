#!/usr/bin/env python3
"""Frozen pre-migration byte identity for the 124 packs and 20 profiles."""
from __future__ import annotations

import hashlib
import json
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
from materialize_draft_contracts import render


class DraftContractIdentityTest(unittest.TestCase):
    def test_pre_migration_bytes(self):
        packs, profiles = render(ROOT)
        rendered = {**{f'mapping-packs/{name}': raw for name, raw in packs.items()},
                    **{f'profiles/{name}': raw for name, raw in profiles.items()}}
        expected = json.loads((ROOT / 'scripts/test/fixtures/draft-contract-sha256.json').read_bytes())
        self.assertEqual(144, len(expected))
        for name, digest in expected.items():
            with self.subTest(name=name):
                self.assertEqual(digest, hashlib.sha256(rendered[name]).hexdigest())


if __name__ == '__main__':
    unittest.main()
