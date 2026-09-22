"""Nightly policy tests: changed sources, shared identity and fail-closed publishing."""
import copy
import importlib.util
import json
import re
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/release'))
from turboism_release import nightly as n
from turboism_release import build_identity as bi
from scripts.release.test_build_identity import FakeGitHub as LedgerGitHub
from scripts.test.test_release_tooling import release, archive, sidecar

SHA = 'a' * 40
OTHER = 'b' * 40


def published(source=SHA, number=7, draft=False):
    receipt = dict(bi.identity(f'1.2.4-0.nightly.{number}', source, str(100+number), 1), buildNumber=number)
    version = receipt['version']
    names = [f'TurboismInstaller-{version}.exe', f'TurboismInstaller-{version}.jar',
             f'turboism-{version}-full.zip', f'turboism-{version}-lite.zip']
    return {'id': 1000+number, 'tag_name': 'v'+version, 'draft': draft, 'prerelease': True,
            'published_at': '2026-09-09T00:00:00Z',
            'body': '<!-- turboism-build-v1 '+json.dumps(receipt)+' -->',
            'assets': [{'name': x, 'state':'uploaded', 'size': 123, 'digest':'sha256:'+'c'*64}
                       for name in names for x in (name,name+'.sha256')]}, receipt


class ReadOnlyGitHub:
    def __init__(self, releases):
        self.releases = releases
        self.calls = []
    def api(self, path, **kwargs):
        self.calls.append(path)
        if kwargs.get('method', 'GET') != 'GET': raise AssertionError('preflight must not mutate state')
        if path.startswith('releases?'): return copy.deepcopy(self.releases)
        if path.startswith('compare/'): return {'status':'ahead'}
        if path.startswith('git/ref/tags/'):
            raw = next(r for r in self.releases if r['tag_name']==path.split('/')[-1])
            return {'object': {'type':'commit','sha': n.receipt_from_body(raw['body'])['sourceRevision']}}
        if path.startswith('contents/entries/'):
            rec = next(n.receipt_from_body(r['body']) for r in self.releases
                       if path.split('/')[2].split('.json')[0] == f"{n.receipt_from_body(r['body'])['runId']}-{n.receipt_from_body(r['body'])['runAttempt']}")
            import base64
            return {'encoding':'base64','content':base64.b64encode(json.dumps(rec).encode()).decode()}
        raise AssertionError(path)


class NightlyPolicyTest(unittest.TestCase):
    def test_first_run_builds(self):
        p = n.plan(ReadOnlyGitHub([]), SHA, '1.2.3')
        self.assertTrue(p['should_build']); self.assertEqual(p['source_sha'], SHA)
    def test_same_successful_source_skips_without_a_counter_write(self):
        api = ReadOnlyGitHub([published()[0]])
        p = n.plan(api, SHA, '1.2.3')
        self.assertFalse(p['should_build']); self.assertEqual(p['reason'], 'already_published')
        self.assertTrue(all('counter.json' not in call for call in api.calls))
    def test_new_source_builds_even_if_commit_is_older_than_24_hours(self):
        self.assertTrue(n.plan(ReadOnlyGitHub([published()[0]]), OTHER, '1.2.3')['should_build'])
    def test_failed_unpublished_build_does_not_become_the_baseline(self):
        old,_=published(OTHER, 6); failed,_=published(SHA, 7, draft=True)
        self.assertTrue(n.plan(ReadOnlyGitHub([old,failed]), SHA, '1.2.3')['should_build'])
    def test_latest_success_uses_verified_build_sequence_not_api_order(self):
        old,_=published(OTHER, 5); current,_=published(SHA, 7)
        self.assertFalse(n.plan(ReadOnlyGitHub([old,current]), SHA, '1.2.3')['should_build'])
    def test_api_failure_is_not_no_change(self):
        class Failed:
            def api(self,*a,**k): raise RuntimeError('HTTP 403')
        with self.assertRaises(RuntimeError): n.plan(Failed(), SHA, '1.2.3')
    def test_malformed_published_nightly_is_not_silently_used_as_baseline(self):
        raw,_=published();raw['assets'].pop()
        with self.assertRaises(ValueError): n.plan(ReadOnlyGitHub([raw]), SHA, '1.2.3')
    def test_next_version_line_is_after_latest_stable_without_changing_source(self):
        raw={'id':1,'tag_name':'v1.2.3','draft':False,'prerelease':False}
        self.assertEqual(n.plan(ReadOnlyGitHub([raw]), SHA,'1.2.3')['base_version'],'1.2.4')
        self.assertEqual(n.plan(ReadOnlyGitHub([raw]), SHA,'1.3.0')['base_version'],'1.3.0')
    def test_nightly_base_never_regresses_behind_prior_nightly(self):
        raw,_=published()
        self.assertEqual(n.plan(ReadOnlyGitHub([raw]), OTHER,'1.2.3')['base_version'],'1.2.4')
    def test_receipt_requires_unique_exact_nightly_identity(self):
        raw,rec=published()
        self.assertEqual(n.receipt_from_body(raw['body']),rec)
        for body in ['',raw['body']*2, raw['body'].replace('1.2.4-0.nightly.7','1.2.4-0.nightly.8')]:
            with self.assertRaises(ValueError): n.receipt_from_body(body)


class NightlyAllocationTest(unittest.TestCase):
    def test_nightly_uses_same_counter_and_version_suffix(self):
        api=LedgerGitHub()
        first=bi.allocate(api, bi.identity('1.2.3', SHA, '40',1))
        second=bi.allocate(api,bi.identity('1.2.4',OTHER,'41',1),nightly=True)
        self.assertEqual(first['buildNumber'],1)
        self.assertEqual(second['buildNumber'],2)
        self.assertEqual(second['version'],'1.2.4-0.nightly.2')
        self.assertEqual(second['channel'],'nightly')
        self.assertEqual(bi.allocate(api,bi.identity('1.2.4',OTHER,'41',1),nightly=True),second)
    def test_rebuilt_attempt_gets_new_number_but_sync_does_not(self):
        api=LedgerGitHub()
        a=bi.allocate(api,bi.identity('1.2.4',SHA,'41',1),nightly=True)
        b=bi.allocate(api,bi.identity('1.2.4',SHA,'41',2),nightly=True)
        self.assertEqual(b['buildNumber'],a['buildNumber']+1)
    def test_allocator_rejects_prerelease_as_a_nightly_base(self):
        with self.assertRaises(ValueError): bi.allocate(LedgerGitHub(),bi.identity('1.2.4-beta.1',SHA,'1',1),nightly=True)


class NightlyPayloadTest(unittest.TestCase):
    def test_nightly_version_is_opt_in_and_stable_rules_are_unchanged(self):
        with self.assertRaises(ValueError): release.release_artifacts(Path('.'),'1.2.4-0.nightly.7')
        self.assertEqual(len(release.release_artifacts(Path('.'),'1.2.4-0.nightly.7',channel='nightly')),8)
        for v in ['1.2.4','1.2.4-beta.7','1.2.4-0.nightly.07']:
            with self.assertRaises(ValueError):release.release_artifacts(Path('.'),v,channel='nightly')
    def test_partial_published_release_or_modified_bytes_cannot_be_success(self):
        raw,rec=published()
        expected={a['name']:{'size':a['size'],'sha256':a['digest'][7:]} for a in raw['assets']}
        self.assertEqual(n.missing_assets(raw, expected, raw['tag_name']),[])
        for mutate in [lambda r:r['assets'].pop(),lambda r:r['assets'][0].update(digest='sha256:'+'e'*64),lambda r:r.update(prerelease=False)]:
            r=copy.deepcopy(raw);mutate(r)
            with self.assertRaises(ValueError):n.missing_assets(r,expected,raw['tag_name'])
    def test_draft_upload_is_resumable_and_never_overwrites(self):
        raw,_=published(draft=True);expected={a['name']:{'size':a['size'],'sha256':a['digest'][7:]} for a in raw['assets']}
        missing=raw['assets'].pop()['name']
        self.assertEqual(n.missing_assets(raw,expected,raw['tag_name']),[missing])


class NightlyPublisherTest(unittest.TestCase):
    def test_upload_failure_leaves_draft_and_retry_does_not_replace_files(self):
        from scripts.test.test_release_promotion import FakeGitHub as BaseRemote
        from turboism_release.promotion import ReleaseError
        raw, receipt = published()
        notes = raw['body']
        class Remote(BaseRemote):
            def api(self, path, *, method='GET', data=None, optional=False):
                result=super().api(path,method=method,data=data,optional=optional)
                if path=='releases' and method=='POST':
                    self.release.update(prerelease=True,body=data['body'])
                if path=='releases/42' and method=='PATCH' and data.get('draft') is False:
                    self.release['published_at']='2026-09-09T01:00:00Z'
                return result
            def upload(self, tag, path):
                super().upload(tag,path)
                self.release['assets'][-1]['state']='uploaded'
        api=Remote({})
        with tempfile.TemporaryDirectory() as folder:
            dist=Path(folder)
            for name in n.names_for(receipt['version']): (dist/name).write_bytes(b'test payload')
            import hashlib
            manifest={'version':receipt['version'],'artifacts':[
                {'name':p.name,'size':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in dist.iterdir()]}
            api.fail_upload=True
            with self.assertRaises(ReleaseError): n.publish(api,dist,receipt,manifest,notes)
            self.assertTrue(api.release['draft'])
            self.assertTrue(api.release['prerelease'])
            n.publish(api,dist,receipt,manifest,notes)
            self.assertFalse(api.release['draft'])
            self.assertEqual(api.release['make_latest'],'false')
            writes=len(api.writes)
            n.publish(api,dist,receipt,manifest,notes)
            self.assertEqual(len(api.writes),writes)


class NightlyWorkflowTest(unittest.TestCase):
    def test_daily_0420_shanghai_and_changes_before_allocation(self):
        text=(ROOT/'.github/workflows/release.yml').read_text()
        self.assertIn("cron: '20 20 * * *'",text)
        self.assertIn('Asia/Shanghai',text)
        self.assertIn("needs.preflight.outputs.should_build == 'true'",text)
        self.assertIn('cancel-in-progress: false',text)
        self.assertNotIn('--force',text)
        self.assertIn('./.github/workflows/allocate-build.yml',text)
        self.assertIn('checkRelease',text)
        self.assertIn("github.ref == 'refs/heads/main'",text)
    def test_sdk_keeps_its_reviewed_bytes_while_product_jars_get_identity(self):
        text = (ROOT / 'gradle/common-java.gradle.kts').read_text()
        self.assertIn('project.path != ":sdk" && turboismBuildNumber.isNotEmpty()', text)
        # This gate must still enforce exact artifact hashes, not just API shape.
        verifier = (ROOT / 'gradle/sdk-api.gradle.kts').read_text()
        gate = verifier.split('val checkSdkV7ExactApiCompatibility', 1)[1].split('val generateSdkApiReport', 1)[0]
        self.assertIn('"verify-exact"', gate)
        self.assertIn('"--input", sdkJarArtifact', gate)

    def test_nightly_publishing_does_not_replace_stable_or_call_legacy(self):
        text=(ROOT/'scripts/release/turboism_release/prerelease.py').read_text()
        self.assertIn('"make_latest":"false"',text)
        self.assertNotIn('updates.turboism.dev',text)
        notify=(ROOT/'.github/workflows/notify-release-api.yml').read_text()
        self.assertIn('Product release candidate',notify)

if __name__=='__main__':unittest.main()
