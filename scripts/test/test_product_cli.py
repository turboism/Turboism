import importlib.util
import io
import json
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
