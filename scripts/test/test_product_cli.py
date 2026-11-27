import importlib.util
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
RELEASE_SCRIPTS = str(ROOT / 'scripts/release')
if RELEASE_SCRIPTS not in sys.path:
    sys.path.insert(0, RELEASE_SCRIPTS)

class ProductCliTest(unittest.TestCase):
    def setUp(self):
        p = ROOT/'scripts/release/product.py'
        self.assertTrue(p.is_file(), 'The local three-channel entry is missing')
        spec=importlib.util.spec_from_file_location('tested_product_cli',p)
        self.cli=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.cli)

    def test_candidate_command_has_channel_not_guessed_build_number(self):
        c=self.cli.candidate_command('beta','1.2.3-beta.2','a'*40)
        self.assertIn('channel=beta',c);self.assertIn('version=1.2.3-beta.2',c)
        self.assertIn('expected_source_sha='+'a'*40,c)
        self.assertFalse(any('buildNumber' in x or 'counter' in x for x in c))

    def test_info_reads_own_embedded_identity_without_network(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'agent.jar'
            with zipfile.ZipFile(p,'w') as z:
                z.writestr('META-INF/turboism/framework-version.properties','version=1.2.3-beta.2\nchannel=beta\nbuildNumber=42\nsourceRevision='+'a'*40+'\nbuildKind=ci\ndirty=false\n')
            info=self.cli.read_jar_info(p)
            self.assertEqual(info['version'],'1.2.3-beta.2');self.assertEqual(info['buildNumber'],42)
            self.assertEqual(info['channel'],'beta')

    def test_historical_version_does_not_gain_a_build_number(self):
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'agent.jar'
            with zipfile.ZipFile(p,'w') as z:z.writestr('META-INF/turboism/framework-version.properties','version=1.2.3\n')
            self.assertIsNone(self.cli.read_jar_info(p)['buildNumber'])

    def test_product_build_preflight_writes_github_output_and_skips_when_unchanged(self):
        import os
        from unittest.mock import patch
        pb_path = ROOT / 'scripts/release/product-build.py'
        spec = importlib.util.spec_from_file_location('tested_product_build', pb_path)
        pb = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pb)

        sha = 'a' * 40
        fake_plan = {'should_build': False, 'reason': 'already_published',
                     'base_version': '0.44.1', 'source_sha': sha, 'previous_source': sha}
        with tempfile.NamedTemporaryFile('w+', delete=False) as output_file:
            output_path = output_file.name
        try:
            env = {
                'GITHUB_ACTIONS': 'true',
                'GITHUB_REPOSITORY': 'turboism/Turboism',
                'GITHUB_REF': 'refs/heads/main',
                'GITHUB_SHA': sha,
                'GITHUB_OUTPUT': output_path,
            }
            with patch.dict(os.environ, env):
                with patch.object(pb, 'check_source'):
                    with patch.object(pb.nightly, 'plan', return_value=fake_plan):
                        ret = pb.main(['preflight', '--source', sha, '--channel', 'nightly', '--version', ''])
            self.assertEqual(ret, 0)
            content = Path(output_path).read_text()
            self.assertIn('should_build=false\n', content)
            self.assertIn('channel=nightly\n', content)
            self.assertIn('version=0.44.1\n', content)
        finally:
            Path(output_path).unlink(missing_ok=True)

    def test_candidate_command_supports_fast_stable_from_beta_and_rejects_other_channels(self):
        sha = 'a' * 40
        command = self.cli.candidate_command('stable', '', sha, beta_run_id='98765', beta_attempt='2')
        self.assertIn('promote_from_beta_run_id=98765', command)
        self.assertIn('promote_from_beta_attempt=2', command)
        with self.assertRaises(ValueError):
            self.cli.candidate_command('beta', '1.2.3-beta.2', sha, beta_run_id='98765', beta_attempt='2')
        with self.assertRaises(ValueError):
            self.cli.candidate_command('stable', '', sha, beta_run_id='98765', beta_attempt='')

    def test_verify_source_beta_run_enforces_same_sha_jobs_and_ledger_binding(self):
        import base64
        import json
        from turboism_release import beta_promotion as bp

        sha = 'a' * 40
        ledger_record = {
            'schemaVersion': 1,
            'version': '0.70.3-beta.6',
            'channel': 'beta',
            'buildNumber': 108,
            'sourceRevision': sha,
            'runId': '555',
            'runAttempt': 1,
        }

        class FakeGitHub:
            def __init__(self, *, run_overrides=None, jobs_override=None, ledger_overrides=None):
                self.run = {
                    'id': 555,
                    'run_attempt': 1,
                    'path': bp.WORKFLOW_PATH,
                    'name': bp.WORKFLOW_NAME,
                    'event': bp.DISPATCH_EVENT,
                    'head_branch': bp.MAIN_BRANCH,
                    'head_sha': sha,
                    'status': bp.COMPLETED_STATUS,
                    'conclusion': bp.SUCCESS_CONCLUSION,
                    'repository': {'full_name': bp.REPOSITORY},
                    'head_repository': {'full_name': bp.REPOSITORY},
                    **(run_overrides or {}),
                }
                self.jobs = jobs_override if jobs_override is not None else {
                    'total_count': 2,
                    'jobs': [
                        {'name': 'build', 'conclusion': 'success'},
                        {'name': 'windows-release-verification', 'conclusion': 'success'},
                    ],
                }
                self.ledger = {**ledger_record, **(ledger_overrides or {})}

            def api(self, path, *, optional=False):
                if path == 'actions/runs/555/attempts/1':
                    return self.run
                if path.startswith('actions/runs/555/attempts/1/jobs'):
                    return self.jobs
                if path.startswith('contents/entries/555-1.json'):
                    encoded = base64.b64encode(json.dumps(self.ledger).encode('utf-8')).decode('ascii')
                    return {'encoding': 'base64', 'content': encoded}
                if path.startswith('actions/workflows/release.yml/runs'):
                    return {'workflow_runs': [{'id': 555, 'run_attempt': 1}]}
                raise AssertionError(path)

        verified = bp.verify_source_beta_run(FakeGitHub(), sha, '0.70.3', '555', 1, current_run_id='777')
        self.assertEqual(verified['version'], '0.70.3-beta.6')
        self.assertEqual(verified['buildNumber'], 108)
        discovered = bp.find_latest_verified_beta_run(FakeGitHub(), sha, '0.70.3')
        self.assertEqual(discovered, verified)

        with self.assertRaises(ValueError):
            bp.verify_source_beta_run(FakeGitHub(), sha, '0.70.3', '555', 1, current_run_id='555')
        with self.assertRaises(ValueError):
            bp.verify_source_beta_run(FakeGitHub(run_overrides={'head_sha': 'b' * 40}), sha, '0.70.3', '555', 1)
        with self.assertRaises(ValueError):
            bp.verify_source_beta_run(
                FakeGitHub(jobs_override={'total_count': 1, 'jobs': [{'name': 'build', 'conclusion': 'success'}]}),
                sha, '0.70.3', '555', 1,
            )
        with self.assertRaises(ValueError):
            bp.verify_source_beta_run(
                FakeGitHub(ledger_overrides={'channel': 'stable', 'version': '0.70.3'}),
                sha, '0.70.3', '555', 1,
            )
        with self.assertRaises(ValueError):
            bp.verify_source_beta_run(FakeGitHub(), sha, '0.70.4', '555', 1)

    def test_product_build_preflight_enables_fast_stable_and_rejects_beta_channel_shortcut(self):
        import os
        from unittest.mock import patch
        pb_path = ROOT / 'scripts/release/product-build.py'
        spec = importlib.util.spec_from_file_location('tested_product_build_fast', pb_path)
        pb = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(pb)

        sha = 'a' * 40
        verified_beta = {
            'runId': '555',
            'runAttempt': 1,
            'version': '0.70.3-beta.6',
            'buildNumber': 108,
            'sourceRevision': sha,
        }
        with tempfile.NamedTemporaryFile('w+', delete=False) as output_file:
            output_path = output_file.name
        try:
            env = {
                'GITHUB_ACTIONS': 'true',
                'GITHUB_REPOSITORY': 'turboism/Turboism',
                'GITHUB_REF': 'refs/heads/main',
                'GITHUB_SHA': sha,
                'GITHUB_EVENT_NAME': 'workflow_dispatch',
                'GITHUB_RUN_ID': '999',
                'GITHUB_OUTPUT': output_path,
            }
            fake_github = type('FakeGH', (), {'api': lambda self, path, optional=False: None})()
            with patch.dict(os.environ, env):
                with patch.object(pb, 'check_source'), \
                     patch.object(pb, 'GitHub', return_value=fake_github), \
                     patch.object(pb, 'framework_version', return_value='0.70.3'), \
                     patch.object(pb, 'verify_source_beta_run', return_value=verified_beta):
                    ret = pb.main([
                        'preflight', '--source', sha, '--channel', 'stable', '--version', '',
                        '--beta-run-id', '555', '--beta-attempt', '1',
                    ])
                    self.assertEqual(ret, 0)
                    with self.assertRaises(ValueError):
                        pb.main([
                            'preflight', '--source', sha, '--channel', 'beta', '--version', '0.70.3-beta.7',
                            '--beta-run-id', '555', '--beta-attempt', '1',
                        ])
            content = Path(output_path).read_text(encoding='utf-8')
            self.assertIn('should_build=true\n', content)
            self.assertIn('channel=stable\n', content)
            self.assertIn('version=0.70.3\n', content)
            self.assertIn('build_mode=fast-stable\n', content)
        finally:
            Path(output_path).unlink(missing_ok=True)
