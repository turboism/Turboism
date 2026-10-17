import importlib.util
import tempfile
import unittest
import zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]

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
