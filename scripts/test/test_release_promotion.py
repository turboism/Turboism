"""Candidate promotion tests: real local Git/payload verification, fake remote mutations."""
from __future__ import annotations

import copy
import hashlib
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts/release"))
from turboism_release import promotion as p
from turboism_release.candidate import framework_artifacts
from scripts.test.test_release_tooling import archive, sidecar


class FakeGitHub:
    def __init__(self, run):
        self.run = run
        self.ref = None
        self.tag_object = None
        self.release = None
        self.latest = None
        self.writes = []
        self.fail_upload = False

    def api(self, path, *, method="GET", data=None, optional=False):
        if method == "GET":
            if path.startswith("actions/runs/"):
                return copy.deepcopy(self.run)
            if path.startswith("git/ref/"):
                return copy.deepcopy(self.ref)
            if path.startswith("git/tags/"):
                return copy.deepcopy(self.tag_object)
            if path == "releases/latest":
                return copy.deepcopy(self.latest)
            if path.startswith("releases/tags/"):
                # GitHub hides unpublished drafts from this endpoint (HTTP 404),
                # so a draft is only observable through the paginated list API.
                if self.release is None or self.release.get("draft") is True:
                    if optional:
                        return None
                    raise p.ReleaseError(f"GitHub GET {path} failed (HTTP 404)")
                if path != "releases/tags/" + self.release["tag_name"]:
                    raise AssertionError(path)
                return copy.deepcopy(self.release)
            if path.startswith("releases?per_page="):
                return [] if self.release is None else [copy.deepcopy(self.release)]
            if path.startswith("releases/"):
                if self.release is not None and path == f"releases/{self.release['id']}":
                    return copy.deepcopy(self.release)
                if optional:
                    return None
                raise p.ReleaseError(f"GitHub GET {path} failed (HTTP 404)")
            raise AssertionError(path)
        self.writes.append((method, path, copy.deepcopy(data)))
        if path == "git/tags":
            self.tag_object = {"sha": "b" * 40, "tag": data["tag"], "message": data["message"],
                               "object": {"type": "commit", "sha": data["object"]}}
            return self.tag_object
        if path == "git/refs":
            self.ref = {"object": {"type": "tag", "sha": data["sha"]}}
            return self.ref
        if path == "releases":
            self.release = {"id": 42, "tag_name": data["tag_name"], "draft": True,
                            "prerelease": False, "assets": []}
            return self.release
        if path.startswith("releases/"):
            if self.release is not None and path == f"releases/{self.release['id']}":
                self.release.update(data)
                return self.release
            raise AssertionError(path)
        raise AssertionError(path)

    def upload(self, tag, path):
        if self.fail_upload:
            self.fail_upload = False
            raise p.ReleaseError("injected upload failure")
        self.writes.append(("upload", tag, path.name))
        self.release["assets"].append({"name": path.name, "size": path.stat().st_size,
            "digest": "sha256:" + hashlib.sha256(path.read_bytes()).hexdigest()})


class PromotionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = self.root / "source"
        self.source.mkdir()
        (self.source / "gradle").mkdir()
        (self.source / "packaging").mkdir()
        (self.source / "gradle/common-java.gradle.kts").write_text(
            'rootProject.extra["turboismFrameworkVersion"] = "1.2.3"\n')
        (self.source / "CHANGELOG.md").write_text('## [1.2.3] - 2026-09-06\n\nTest notes\n')
        (self.source / "packaging/release-plugins.txt").write_text(':plugins:core\n:plugins:mcp\n')
        self.git("init", "-b", "main")
        self.git("add", ".")
        self.git("-c", "user.name=Test", "-c", "user.email=test@example.invalid", "commit", "-m", "fixture")
        self.sha = self.git("rev-parse", "HEAD")
        self.bundle = self.root / "bundle"
        self.dist = self.bundle / "windows-installer/dist"
        self.dist.mkdir(parents=True)
        (self.dist.parent / "staging").mkdir()
        archive(self.dist / 'turboism-1.2.3-lite.zip', '1.2.3')
        archive(self.dist / 'turboism-1.2.3-full.zip', '1.2.3', ('mcp',))
        (self.dist / 'TurboismInstaller-1.2.3.exe').write_bytes(b'exe')
        (self.dist / 'TurboismInstaller-1.2.3.jar').write_bytes(b'jar')
        for path in list(self.dist.iterdir()):
            sidecar(path)
        self.candidate = {"format": "turboism.release-candidate", "schemaVersion": 1,
            "source": {"repository": p.REPOSITORY, "revision": self.sha, "tag": "v1.2.3"},
            "framework": {"eligible": True, "version": "1.2.3", "changelog": {
                "date": "2026-09-06", "sha256": hashlib.sha256(b'Test notes\n').hexdigest()},
                "artifacts": framework_artifacts(self.source, self.dist, '1.2.3')},
            "plugins": {"candidates": []}}
        self.save_candidate()
        (self.bundle / 'release-notes.md').write_text('Test notes\n')
        self.run = {"id": 123, "name": "Product release candidate", "path": p.WORKFLOW,
            "event": "workflow_dispatch", "head_branch": "main", "head_sha": self.sha,
            "status": "completed", "conclusion": "success", "run_attempt": 2,
            "repository": {"full_name": p.REPOSITORY}, "head_repository": {"full_name": p.REPOSITORY}}
        self.gh = FakeGitHub(self.run)

    def git(self, *args):
        return subprocess.check_output(['git', '-C', str(self.source), *args], text=True,
                                       stderr=subprocess.DEVNULL).strip()

    def save_candidate(self):
        (self.bundle / 'candidate.json').write_text(json.dumps(self.candidate))

    def promote(self, attempt=2):
        return p.promote(self.gh, self.source, self.bundle, '123', self.sha, attempt,
                         'publish-github-only:' + self.sha)

    def test_promotes_exact_bytes_and_annotated_source_then_noops(self):
        self.promote()
        self.assertEqual(self.gh.tag_object['object']['sha'], self.sha)
        self.assertEqual(self.gh.ref['object']['type'], 'tag')
        self.assertFalse(self.gh.release['draft'])
        self.assertEqual(len(self.gh.release['assets']), 8)
        writes = copy.deepcopy(self.gh.writes)
        self.promote()
        self.assertEqual(self.gh.writes, writes)

    def test_failures_never_create_refs_releases_or_assets(self):
        mutations = [('conclusion', 'failure'), ('status', 'in_progress'), ('event', 'pull_request'),
                     ('head_sha', 'c' * 40), ('head_branch', 'feature'), ('run_attempt', 3),
                     ('path', '.github/workflows/ci.yml'), ('id', 456),
                     ('head_repository', {'full_name': 'attacker/fork'})]
        for key, value in mutations:
            with self.subTest(key=key):
                self.gh = FakeGitHub({**self.run, key: value})
                with self.assertRaises(p.ReleaseError):
                    self.promote()
                self.assertEqual(self.gh.writes, [])

    def test_new_candidate_requires_attempt_and_confirmation(self):
        with self.assertRaises(p.ReleaseError):
            self.promote(None)
        with self.assertRaises(p.ReleaseError):
            p.promote(self.gh, self.source, self.bundle, '123', self.sha, 2, 'yes')
        self.assertEqual(self.gh.writes, [])

    def test_payload_tampering_rejected_before_mutation(self):
        (self.dist / 'TurboismInstaller-1.2.3.exe').write_bytes(b'tampered')
        with self.assertRaises(p.ReleaseError):
            self.promote()
        self.assertEqual(self.gh.writes, [])

    def test_notes_and_source_binding_rejected_before_mutation(self):
        for key, value in [('revision', 'c' * 40), ('tag', 'v1.2.4'), ('repository', 'attacker/fork')]:
            self.candidate['source'][key] = value
            self.save_candidate()
            with self.assertRaises(p.ReleaseError):
                self.promote()
            self.assertEqual(self.gh.writes, [])
            self.candidate['source'] = {'revision': self.sha, 'tag': 'v1.2.3', 'repository': p.REPOSITORY}
        self.save_candidate()
        (self.bundle / 'release-notes.md').write_text('different notes')
        with self.assertRaises(p.ReleaseError):
            self.promote()
        self.assertEqual(self.gh.writes, [])

    def test_missing_duplicate_and_symlinked_payload_rejected(self):
        file = self.bundle / 'candidate.json'
        original = file.read_bytes()
        file.unlink()
        with self.assertRaises(p.ReleaseError):
            self.promote()
        file.write_bytes(original)
        other = self.bundle / 'duplicate'
        other.mkdir()
        (other / 'candidate.json').write_bytes(original)
        with self.assertRaises(p.ReleaseError):
            self.promote()
        (other / 'candidate.json').unlink()
        (other / 'link').symlink_to(self.source)
        with self.assertRaises(p.ReleaseError):
            self.promote()
        self.assertEqual(self.gh.writes, [])

    def test_partial_upload_resumes_same_tag_without_rebuild(self):
        self.gh.fail_upload = True
        with self.assertRaises(p.ReleaseError):
            self.promote()
        self.assertTrue(self.gh.release['draft'])
        self.promote()
        self.assertEqual(sum(path == 'git/refs' for _, path, _ in self.gh.writes), 1)
        self.assertEqual(len(self.gh.release['assets']), 8)
        self.assertFalse(self.gh.release['draft'])

    def test_conflicting_tag_or_existing_assets_never_overwritten(self):
        self.promote()
        self.gh.writes.clear()
        self.gh.tag_object['object']['sha'] = 'c' * 40
        with self.assertRaises(p.ReleaseError):
            self.promote()
        self.assertEqual(self.gh.writes, [])
        self.gh.tag_object['object']['sha'] = self.sha
        self.gh.release['assets'][0]['digest'] = 'sha256:' + 'c' * 64
        with self.assertRaises(p.ReleaseError):
            self.promote()
        self.assertEqual(self.gh.writes, [])

    def test_newer_release_prevents_downgrade(self):
        self.gh.latest = {'tag_name': 'v1.2.4'}
        with self.assertRaises(p.ReleaseError):
            self.promote()
        self.assertEqual(self.gh.writes, [])

    def test_legacy_successful_push_candidate_remains_supported(self):
        self.gh.run.update(event='push', head_branch='v1.2.3')
        # Legacy inputs need no explicit attempt; the successful run still binds its artifact name.
        self.promote(None)
        self.assertFalse(self.gh.release['draft'])

    def test_local_candidate_tag_never_pushes_and_preserves_remote(self):
        remote = self.root / 'remote.git'
        subprocess.run(['git', 'init', '--bare', str(remote)], check=True, capture_output=True)
        self.git('remote', 'add', 'origin', str(remote))
        p.prepare_candidate_tag(self.source, self.sha)
        self.assertEqual(self.git('cat-file', '-t', 'refs/tags/v1.2.3'), 'tag')
        self.assertEqual(self.git('ls-remote', 'origin', 'refs/tags/v1.2.3'), '')
        with self.assertRaises(p.ReleaseError):
            p.prepare_candidate_tag(self.source, 'c' * 40)

    def test_same_version_candidates_have_distinct_identities_without_remote_tags(self):
        second = self.root / 'second-runner'
        subprocess.run(['git', 'clone', str(self.source), str(second)], check=True, capture_output=True)
        (second / 'fix.txt').write_text('candidate fix, same version')
        subprocess.run(['git', '-C', str(second), 'add', '.'], check=True)
        subprocess.run(['git', '-C', str(second), '-c', 'user.name=Test', '-c',
                        'user.email=test@example.invalid', 'commit', '-m', 'fix'], check=True, capture_output=True)
        other_sha = subprocess.check_output(['git', '-C', str(second), 'rev-parse', 'HEAD'], text=True).strip()
        self.assertNotEqual(other_sha, self.sha)
        self.assertEqual(p.prepare_candidate_tag(self.source, self.sha), 'v1.2.3')
        self.assertEqual(p.prepare_candidate_tag(second, other_sha), 'v1.2.3')
        one = p.validate_run(self.run, '123', self.sha, 2)
        two = p.validate_run({**self.run, 'id': 124, 'head_sha': other_sha}, '124', other_sha, 2)
        self.assertNotEqual(one['artifact_name'], two['artifact_name'])
        self.assertEqual(self.gh.writes, [])

    def test_source_metadata_modified_after_checkout_is_rejected(self):
        (self.source / 'packaging/release-plugins.txt').write_text(':plugins:core\n')
        with self.assertRaises(p.ReleaseError):
            self.promote()
        self.assertEqual(self.gh.writes, [])

    def test_main_advancing_cannot_retarget_candidate(self):
        # The remote branch is not consulted as the tag source; only the checked-out run SHA is used.
        self.gh.main_sha = 'd' * 40
        self.promote()
        self.assertEqual(self.gh.tag_object['object']['sha'], self.sha)

    def test_rejects_lightweight_and_incomplete_published_releases(self):
        self.gh.ref = {'object': {'type': 'commit', 'sha': self.sha}}
        with self.assertRaises(p.ReleaseError):
            self.promote()
        self.assertEqual(self.gh.writes, [])
        self.gh.ref = None
        self.promote()
        self.gh.writes.clear()
        self.gh.release['assets'].pop()
        with self.assertRaises(p.ReleaseError):
            self.promote()
        self.assertEqual(self.gh.writes, [])

    def test_conflicting_existing_draft_is_checked_before_tag_creation(self):
        self.gh.release = {'id': 42, 'tag_name': 'v1.2.3', 'draft': True,
                           'prerelease': False, 'assets': []}
        with self.assertRaises(p.ReleaseError):
            self.promote()
        self.assertEqual(self.gh.writes, [])

    def test_lost_ref_creation_response_resumes_only_matching_identity(self):
        original = self.gh.api
        def race(path, **kwargs):
            result = original(path, **kwargs)
            if path == 'git/refs' and kwargs.get('method') == 'POST':
                raise p.ReleaseError('lost response after ref creation')
            return result
        self.gh.api = race
        self.promote()
        self.assertFalse(self.gh.release['draft'])
        self.assertEqual(sum(path == 'git/refs' for _, path, _ in self.gh.writes), 1)

    def test_competing_candidate_cannot_overwrite_bound_version(self):
        original = self.gh.api
        def race(path, **kwargs):
            result = original(path, **kwargs)
            if path == 'git/refs' and kwargs.get('method') == 'POST':
                self.gh.tag_object['object']['sha'] = 'c' * 40
                raise p.ReleaseError('another source won')
            return result
        self.gh.api = race
        with self.assertRaises(p.ReleaseError):
            self.promote()
        self.assertIsNone(self.gh.release)
        self.assertFalse(any(method == 'PATCH' for method, _, _ in self.gh.writes))


    def test_tag_binds_payload_even_when_draft_creation_did_not_finish(self):
        original = self.gh.api
        def fail_draft(path, **kwargs):
            if path == 'releases' and kwargs.get('method') == 'POST':
                raise p.ReleaseError('interrupted before draft')
            return original(path, **kwargs)
        self.gh.api = fail_draft
        with self.assertRaises(p.ReleaseError):
            self.promote()
        self.assertIsNotNone(self.gh.ref)
        self.assertIsNone(self.gh.release)
        self.gh.api = original
        self.gh.writes.clear()
        exe = self.dist / 'TurboismInstaller-1.2.3.exe'
        exe.write_bytes(b'another valid build at same source')
        sidecar(exe)
        self.candidate['framework']['artifacts'] = framework_artifacts(self.source, self.dist, '1.2.3')
        self.save_candidate()
        with self.assertRaisesRegex(p.ReleaseError, 'payload conflict'):
            self.promote()
        self.assertEqual(self.gh.writes, [])

    def test_run_rerun_during_preflight_cannot_publish_old_attempt(self):
        original = self.gh.api
        observations = []
        def rerun(path, **kwargs):
            if path.startswith('actions/runs/'):
                observations.append(path)
                if len(observations) > 1:
                    self.gh.run['run_attempt'] = 3
            return original(path, **kwargs)
        self.gh.api = rerun
        with self.assertRaises(p.ReleaseError):
            self.promote()
        self.assertEqual(self.gh.writes, [])


class TransportAndWorkflowTest(unittest.TestCase):
    def test_only_confirmed_404_means_absent(self):
        from unittest.mock import patch
        for status in [None, 403, 404, 500]:
            with self.subTest(status=status):
                stdout = '' if status is None else f'HTTP/2.0 {status} Error\r\nContent-Type: application/json\r\n\r\n{{}}'
                result = subprocess.CompletedProcess([], 1, stdout, 'not found')
                with patch.object(p.subprocess, 'run', return_value=result):
                    if status == 404:
                        self.assertIsNone(p.GitHub().api('git/ref/tags/v1.2.3', optional=True))
                    else:
                        with self.assertRaises(p.ReleaseError):
                            p.GitHub().api('git/ref/tags/v1.2.3', optional=True)

    def test_transport_parses_success_and_never_force_uploads(self):
        from unittest.mock import patch
        response = subprocess.CompletedProcess([], 0, 'HTTP/2.0 200 OK\nContent-Type: application/json\n\n{"id":123}', '')
        with patch.object(p.subprocess, 'run', return_value=response) as run:
            self.assertEqual(p.GitHub().api('actions/runs/123'), {'id': 123})
            p.GitHub().upload('v1.2.3', Path('fixture.zip'))
            self.assertNotIn('--clobber', run.call_args.args[0])

    def test_protected_promotion_uses_trusted_tooling_and_exact_artifact(self):
        workflow = (ROOT / '.github/workflows/release-github-only.yml').read_text()
        self.assertIn("if: github.ref == 'refs/heads/main'", workflow)
        self.assertIn('environment: production-release', workflow)
        self.assertIn('group: turboism-production-release', workflow)
        self.assertIn('path: release-tools', workflow)
        self.assertIn('path: release-source', workflow)
        self.assertIn('ref: ${{ github.sha }}', workflow)
        self.assertIn('name: ${{ steps.source.outputs.artifact_name }}', workflow)
        self.assertIn('run-id: ${{ inputs.candidate_run_id }}', workflow)
        self.assertIn('--attempt "$RUN_ATTEMPT"', workflow)
        self.assertNotIn('./gradlew', workflow)
        self.assertNotIn('--clobber', workflow)
        self.assertLess(workflow.index('Download verified candidate payload'),
                        workflow.index('promote-github-release.py promote'))
        legacy = (ROOT / '.github/workflows/release-publisher.yml').read_text()
        self.assertIn("github.event.workflow_run.name != 'Product release candidate'", legacy)
        self.assertIn("github.event.workflow_run.event == 'push'", legacy)

    def test_direct_local_promotion_is_refused_without_remote_calls(self):
        from unittest.mock import patch
        import importlib.util
        spec = importlib.util.spec_from_file_location('promotion_cli', ROOT / 'scripts/release/promote-github-release.py')
        cli = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cli)
        with patch.dict(cli.os.environ, {}, clear=True), patch.object(cli.GitHub, 'api') as api:
            self.assertEqual(cli.main(['promote', '--run-id', '123', '--source-sha', 'a' * 40,
                                      '--attempt', '1', '--source-root', '.', '--bundle-root', '.',
                                      '--confirmation', 'publish-github-only:' + 'a' * 40]), 1)
            api.assert_not_called()


if __name__ == '__main__':
    unittest.main()
