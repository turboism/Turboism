"""Beta and Nightly share verified publication, never a mutable channel toggle."""
import copy
import hashlib
import importlib
import json
import sys
import tempfile
import unittest
from pathlib import Path
ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT/'scripts/release'))
from turboism_release.build_identity import identity

class PrereleaseTest(unittest.TestCase):
    def setUp(self):
        try:self.p=importlib.import_module('turboism_release.prerelease')
        except ModuleNotFoundError:self.fail('Shared prerelease publication is not implemented')

    def receipt(self, version='1.2.3-beta.2'):
        return dict(identity(version, 'a'*40, '123', 1), buildNumber=7)

    def test_receipt_channel_is_part_of_immutable_identity(self):
        r=self.receipt();body='<!-- turboism-build-v1 '+json.dumps(r)+' -->'
        self.assertEqual(self.p.receipt_from_body(body,'beta'),r)
        with self.assertRaises(ValueError):self.p.receipt_from_body(body,'nightly')
        with self.assertRaises(ValueError):self.p.receipt_from_body(body*2,'beta')

    def test_both_prereleases_require_exactly_eight_assets(self):
        for version in ['1.2.3-beta.2','1.2.3-0.nightly.7']:
            names=self.p.names_for(version);self.assertEqual(len(names),8)
        with self.assertRaises(ValueError):self.p.names_for('1.2.3')

    def test_beta_publication_resumes_identical_files_and_never_becomes_latest(self):
        from scripts.test.test_release_promotion import FakeGitHub as Base
        from turboism_release.promotion import ReleaseError
        class Remote(Base):
            def api(self,path,*,method='GET',data=None,optional=False):
                result=super().api(path,method=method,data=data,optional=optional)
                if path=='releases' and method=='POST':self.release.update(prerelease=True,body=data['body'])
                if path=='releases/42' and method=='PATCH' and data.get('draft') is False:self.release['published_at']='2026-09-09T00:00:00Z'
                return result
            def upload(self,tag,path):
                super().upload(tag,path);self.release['assets'][-1]['state']='uploaded'
        api=Remote({});receipt=self.receipt();notes='<!-- turboism-build-v1 '+json.dumps(receipt)+' -->'
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            for name in self.p.names_for(receipt['version']):(root/name).write_bytes(b'checked')
            manifest={'version':receipt['version'],'artifacts':[{'name':f.name,'size':f.stat().st_size,'sha256':hashlib.sha256(f.read_bytes()).hexdigest()} for f in root.iterdir()]}
            api.fail_upload=True
            with self.assertRaises(ReleaseError):self.p.publish(api,root,receipt,manifest,notes)
            self.assertTrue(api.release['draft'])
            self.p.publish(api,root,receipt,manifest,notes)
            self.assertFalse(api.release['draft']);self.assertTrue(api.release['prerelease']);self.assertEqual(api.release['make_latest'],'false')
            count=len(api.writes);self.p.publish(api,root,receipt,manifest,notes);self.assertEqual(len(api.writes),count)
            api.release['assets'][0]['digest']='sha256:'+'b'*64
            with self.assertRaises(ValueError):self.p.publish(api,root,receipt,manifest,notes)
