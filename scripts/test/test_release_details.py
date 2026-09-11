"""Frozen history and locale data must not weaken immutable candidate publication."""
import importlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts/release'))
from turboism_release import prerelease
from turboism_release.build_identity import identity

class ReleaseDetailsTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.git('init','-q')
        self.git('config','user.name','Tests'); self.git('config','user.email','tests@example.invalid')
        self.base = self.commit('initial')
        self.head = self.commit('fix: preserve <original> & safe subjects')
        self.receipt = {**identity('1.2.3-0.nightly.7',self.head,'123',1),'buildNumber':7}

    def git(self,*args):
        return subprocess.run(['git','-C',str(self.root),*args],check=True,capture_output=True,text=True).stdout.strip()

    def commit(self,subject):
        p=self.root/'file.txt';p.write_text(subject);self.git('add','file.txt');self.git('commit','-qm',subject)
        return self.git('rev-parse','HEAD')

    def module(self):
        try:return importlib.import_module('turboism_release.release_notes')
        except ModuleNotFoundError:self.fail('Frozen localized release notes are missing')

    def test_nightly_renders_real_changes_and_does_not_follow_later_commits(self):
        m=self.module();context=m.history_context(self.root,self.head,self.base,'v1.2.2')
        notes=m.render_nightly(self.receipt,context)
        for lang in ['en','zh','ja']:
            self.assertIn('fix: preserve',notes[lang]);self.assertIn(self.head,notes[lang])
            self.assertIn(f'{self.base}...{self.head}',notes[lang])
        original=dict(notes);self.commit('later change must not appear')
        self.assertEqual(m.validate_context(self.root,self.receipt,context),context)
        self.assertEqual(m.render_nightly(self.receipt,context),original)

    def test_context_tampering_and_nonancestor_fail(self):
        m=self.module();c=m.history_context(self.root,self.head,self.base,'v1.2.2')
        changed=json.loads(json.dumps(c));changed['commits'][0]['subject']='invented change'
        with self.assertRaises(ValueError):m.validate_context(self.root,self.receipt,changed)
        with self.assertRaises(ValueError):m.history_context(self.root,self.base,self.head,'v1.2.3')

    def test_preparation_freezes_v2_context_verification_never_selects_remote_baseline(self):
        m=self.module();bundle=self.root/'bundle';bundle.mkdir();context=m.history_context(self.root,self.head,self.base,'v1.2.2')
        with patch.object(prerelease,'verify_files',return_value=({'version':self.receipt['version']},self.receipt)), \
             patch.object(m,'select_context',return_value=context) as select:
            value=prerelease.prepare(object(),self.root,bundle,self.head,'123',1,self.receipt['version'])
            self.assertEqual(value['schemaVersion'],2);self.assertEqual(value['notesContext'],context)
            select.side_effect=AssertionError('Publishing must not query a moving baseline')
            manifest,receipt,notes=prerelease.verify_candidate(object(),self.root,bundle,self.head,'123',1,'nightly')
            self.assertIn('fix: preserve',notes);self.assertEqual(receipt,self.receipt)

    def test_v1_candidate_notes_still_verify_unchanged(self):
        import hashlib
        bundle=self.root/'bundle';bundle.mkdir();notes=prerelease.notes_for(self.root,self.receipt)
        manifest={'version':self.receipt['version']}
        value={'schemaVersion':1,'type':'turboism.prerelease-candidate','receipt':self.receipt,'manifest':manifest,'notesSha256':hashlib.sha256(notes.encode()).hexdigest()}
        (bundle/'release-notes.md').write_text(notes);(bundle/'prerelease-candidate.json').write_text(json.dumps(value))
        with patch.object(prerelease,'verify_files',return_value=(manifest,self.receipt)):
            self.assertEqual(prerelease.verify_candidate(object(),self.root,bundle,self.head,'123',1)[2],notes)

    def test_stable_locales_bind_to_reviewed_english_and_legacy_body_stays_identical(self):
        import hashlib
        m=self.module();english='### Added\n\n- Change'
        self.assertEqual(m.stable_metadata(self.root,'1.2.3',self.head,english),'')
        marker=self.root/'scripts/release/turboism_release/release_notes.py';marker.parent.mkdir(parents=True);marker.write_text('# new pipeline')
        path=self.root/'release-notes/1.2.3.json';path.parent.mkdir()
        path.write_text(json.dumps({'schemaVersion':1,'version':'1.2.3','englishSha256':hashlib.sha256(english.encode()).hexdigest(),'locales':{'zh':'新增改动','ja':'変更を追加'}}))
        text=m.stable_metadata(self.root,'1.2.3',self.head,english)
        self.assertIn('新增改动',text);self.assertIn(self.head,text)
        with self.assertRaises(ValueError):m.stable_metadata(self.root,'1.2.3',self.head,english+' changed')

    def test_first_nightly_uses_published_ancestral_stable_not_later_release(self):
        m=self.module();raws=[{'tag_name':'v1.2.2','draft':False,'prerelease':False,'published_at':'2026-09-01T00:00:00Z'},
          {'tag_name':'v1.2.3','draft':False,'prerelease':False,'published_at':'2026-09-20T00:00:00Z'}]
        class API:
            def api(_,path,**kwargs):
                if path.startswith('releases?'):return raws
                if path=='git/ref/tags/v1.2.2':return {'object':{'type':'commit','sha':self.base}}
                raise AssertionError(path)
        c=m.select_context(API(),self.root,self.receipt,cutoff='2026-09-11T00:00:00Z')
        self.assertEqual(c['baseRevision'],self.base);self.assertEqual(c['baseTag'],'v1.2.2')

if __name__=='__main__':unittest.main()
