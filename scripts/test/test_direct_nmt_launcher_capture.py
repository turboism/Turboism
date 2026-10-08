"""Verify final diagnostic config capture never changes launchers or loses failures."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

SPEC = importlib.util.spec_from_file_location('capture', Path(__file__).resolve().parents[1] / 'preview/capture-direct-nmt-launcher.py')
capture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(capture)


class FinalCaptureTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.task = Path(self.temp.name).resolve()
        self.evidence = self.task / 'evidence'; self.evidence.mkdir()
        self.prefix = self.task / 'prefix'
        app = self.prefix / 'pfx/drive_c/Program Files/Live2D Cubism 5.3.03'; app.mkdir(parents=True)
        self.proxy = app / 'ProxyConfig.bat'
        self.bat = app / 'CubismEditor5.bat'
        self.original = b'original config'
        self.staged = self.original + capture.SUFFIX
        self.proxy.write_bytes(self.staged); self.bat.write_bytes(b'official')
        (self.evidence / 'nmt-proxy-before.bin').write_bytes(self.original)
        (self.evidence / 'nmt-proxy-after.bin').write_bytes(self.staged)
        (self.evidence / 'direct-nmt-launcher.json').write_text(json.dumps({'stagedProxySha256': capture.digest(self.staged)}))
        patch = mock.patch.multiple(capture, ORIGINAL_SHA=capture.digest(self.original), OFFICIAL_SHA=capture.digest(b'official'))
        patch.start(); self.addCleanup(patch.stop)

    def run_capture(self, owner=None):
        return capture.capture(self.task, self.evidence, self.prefix, str(self.task) if owner is None else owner)

    def test_preserves_final_bytes_and_refuses_overwrite(self):
        self.assertEqual('PASS_FINAL_TASK_CONFIG_UNCHANGED', self.run_capture()['status'])
        self.assertEqual(self.staged, self.proxy.read_bytes())
        self.assertEqual(b'official', self.bat.read_bytes())
        self.assertEqual(self.staged, (self.evidence / 'nmt-proxy-final.bin').read_bytes())
        with self.assertRaisesRegex(ValueError, 'already exists'): self.run_capture()

    def test_changed_config_is_saved_as_failure(self):
        self.proxy.write_bytes(b'changed config')
        with self.assertRaisesRegex(ValueError, 'FAIL_FINAL'): self.run_capture()
        self.assertEqual(b'changed config', (self.evidence / 'nmt-proxy-final.bin').read_bytes())
        self.assertEqual('FAIL_FINAL_TASK_CONFIG_CHANGED', json.loads((self.evidence / 'direct-nmt-final-launcher.json').read_text())['status'])

    def test_wrong_owner_and_symlink_are_rejected(self):
        with self.assertRaisesRegex(ValueError, 'task-owned'): self.run_capture('/other')
        self.proxy.unlink(); self.proxy.symlink_to(self.evidence / 'nmt-proxy-after.bin')
        with self.assertRaisesRegex(ValueError, 'regular task-owned'): self.run_capture()
        self.assertFalse((self.evidence / 'direct-nmt-final-launcher.json').exists())

    def test_corrupt_staging_evidence_is_rejected(self):
        (self.evidence / 'nmt-proxy-before.bin').write_bytes(b'changed')
        with self.assertRaisesRegex(ValueError, 'unreviewed'): self.run_capture()
        self.assertFalse((self.evidence / 'nmt-proxy-final.bin').exists())
