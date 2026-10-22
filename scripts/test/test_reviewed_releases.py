"""Canonical trust-root validation and generated-output drift regressions."""
from __future__ import annotations

import copy
import json
from pathlib import Path
import tempfile
import unittest

from generate_reviewed_releases import ENV, HOST, TABLE, TEMPLATE, check, render
from preview.reviewed_host_artifacts import SOURCE_RELATIVE, compact, default_source, load, load_releases
from scripts.test.reviewed_releases_fixture import table

ROOT = Path(__file__).resolve().parents[2]


class ReviewedReleasesTest(unittest.TestCase):
    def test_malformed_trust_roots_fail_closed(self):
        original = json.loads(table(['5.3.03', '5.3.04']))
        mutations = [
            lambda d: d['releases'].reverse(),
            lambda d: d['releases'].append(copy.deepcopy(d['releases'][0])),
            lambda d: d['releases'][0].update(build=True),
            lambda d: d['releases'][0].update(fullRuntime='true'),
            lambda d: d['releases'][0]['artifact'].update(size=0),
            lambda d: d['releases'][0]['artifact'].update(sha256='unknown'),
            lambda d: d['releases'][0]['core'].update(patch=-1),
            lambda d: d['releases'][0].update(hostLabel='../host'),
            lambda d: d['releases'][0].update(unreviewed=True),
        ]
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'reviewed-releases.json'
            for mutate in mutations:
                data = copy.deepcopy(original)
                mutate(data)
                path.write_text(json.dumps(data))
                with self.subTest(mutation=mutate), self.assertRaises(ValueError):
                    load_releases(path)
            path.write_text('{"format": 1, "format": 2}')
            with self.assertRaisesRegex(ValueError, 'duplicate JSON key'):
                load_releases(path)

    def test_new_version_and_explicit_full_runtime_remain_separate(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            path = root / SOURCE_RELATIVE
            path.parent.mkdir(parents=True)
            data = json.loads(table(['5.3.04', '5.3.05']))
            data['releases'][1]['fullRuntime'] = False
            path.write_text(json.dumps(data))
            for relative in (TEMPLATE, ENV):
                dest = root / relative
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes((ROOT / relative).read_bytes())
            outputs = render(root)
            self.assertIn('CUBISM_5_3_05_VERSION = "5.3.05"', outputs[HOST])
            self.assertIn('CUBISM_5_3_05_VERSION, 2, 5, 0, 0, false)', outputs[TABLE])
            self.assertEqual(['5304', '5305'], [compact(v) for v in load(path)])
            self.assertIn('FIXTURE_5305=', outputs[ENV])
            for relative, text in outputs.items():
                (root / relative).parent.mkdir(parents=True, exist_ok=True)
                (root / relative).write_text(text)
            self.assertEqual([], check(root))
            (root / HOST).write_text(outputs[HOST].replace('5.3.05', '5.3.99'))
            self.assertTrue(any('ReviewedHostArtifacts' in problem for problem in check(root)))
            path.unlink()
            with self.assertRaises(ValueError):
                load(path)


if __name__ == '__main__':
    unittest.main()
