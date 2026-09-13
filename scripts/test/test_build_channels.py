"""End-to-end policy inputs for the shared three-channel build entry."""
import importlib
import json
import os
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/release'))

class BuildChannelsTest(unittest.TestCase):
    def setUp(self):
        try:
            self.c = importlib.import_module('turboism_release.channels')
        except ModuleNotFoundError:
            self.fail('The unified channel resolver is not implemented')

    def test_stable_defaults_to_committed_base_without_rewriting_source(self):
        self.assertEqual(self.c.resolve_version('1.2.3', 'stable'), '1.2.3')
        with self.assertRaises(ValueError):
            self.c.resolve_version('1.2.3', 'stable', '1.2.4')

    def test_beta_requires_an_explicit_valid_same_series_version(self):
        self.assertEqual(self.c.resolve_version('1.2.3', 'beta', '1.2.3-beta.10'), '1.2.3-beta.10')
        for invalid in ['', '1.2.3', '1.2.4-beta.1', '1.2.3-beta.01', '1.2.3-0.nightly.1', '1.2.3-beta.local', '1.2.3-SNAPSHOT']:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.c.resolve_version('1.2.3', 'beta', invalid)

    def test_nightly_suffix_is_allocated_not_supplied_by_the_operator(self):
        self.assertEqual(self.c.resolve_version('1.2.3', 'nightly'), '1.2.3')
        with self.assertRaises(ValueError):
            self.c.resolve_version('1.2.3', 'nightly', '1.2.3-0.nightly.42')

    def test_channel_identity_cannot_change_after_build(self):
        for version, channel in [('1.2.3','stable'),('1.2.3-beta.2','beta'),('1.2.3-rc.1','beta'),('1.2.3-0.nightly.7','nightly')]:
            self.assertEqual(self.c.channel_for(version), channel)
            self.c.require_version(version, channel)
            for other in {'stable','beta','nightly'}-{channel}:
                with self.assertRaises(ValueError):self.c.require_version(version, other)

    def test_unknown_and_local_versions_are_not_published(self):
        for v in ['v1.2.3','01.2.3','1.2.3+build.8','1.2.3-0.nightly.0','1.2.3-nightly.7','1.2.3-beta.local','1.2.3-SNAPSHOT']:
            with self.subTest(v=v), self.assertRaises(ValueError):self.c.channel_for(v)

    def test_manual_and_daily_workflow_share_channel_and_packaging_identity(self):
        text = (ROOT/'.github/workflows/release.yml').read_text()
        self.assertIn('options: [stable, beta, nightly]', text)
        self.assertIn("cron: '20 20 * * *'", text)
        self.assertIn("needs.preflight.outputs.should_build == 'true'", text)
        self.assertIn('TURBOISM_BUILD_CHANNEL:', text)
        self.assertIn('TURBOISM_BUILD_VERSION:', text)
        self.assertNotIn('TURBOISM_NIGHTLY_VERSION:', text)
        self.assertIn('checkRelease', text)
        self.assertIn("needs.preflight.outputs.channel == 'nightly'", text)

if __name__ == '__main__':unittest.main()
