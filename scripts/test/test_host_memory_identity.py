#!/usr/bin/env python3
"""Read-only observer identity regression fixtures; never starts or signals a host."""
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location('host_memory_identity', Path(__file__).with_name('host_memory_identity.py'))
IDENTITY = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = IDENTITY
SPEC.loader.exec_module(IDENTITY)


class MemoryIdentityTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.proc = self.root / 'proc'
        self.cg = self.root / 'cgroup'
        self.path = '/user.slice/turboism-queue-' + 'a' * 32 + '.scope'
        self.directory = self.cg / self.path.lstrip('/')
        self.directory.mkdir(parents=True)
        (self.directory / 'cgroup.procs').write_text('10\n20\n')
        boot = self.proc / 'sys/kernel/random/boot_id'
        boot.parent.mkdir(parents=True)
        boot.write_text('11111111-1111-1111-1111-111111111111\n')
        self.process(10, 'python3', ('python3', 'observer.py'))
        self.process(20)

    def process(self, pid, comm='java.exe', command=('java.exe', 'com.live2d.cubism.CECubismEditorApp'), started=42, uid=None, scope=None):
        directory = self.proc / str(pid)
        directory.mkdir(exist_ok=True)
        fields = ['S', '1'] + ['0'] * 17 + [str(started)] + ['0'] * 3
        (directory / 'stat').write_text(f'{pid} ({comm}) ' + ' '.join(fields))
        (directory / 'status').write_text(f'Uid:\t{os.getuid() if uid is None else uid}\t0\t0\t0\n')
        (directory / 'comm').write_text(comm + '\n')
        (directory / 'cmdline').write_bytes(b'\0'.join(x.encode() for x in command) + b'\0')
        (directory / 'cgroup').write_text('0::' + (scope or self.path) + '\n')

    def bind(self):
        scope = IDENTITY.BoundScope(self.proc, self.cg, observer_pid=10)
        self.addCleanup(scope.close)
        return scope

    def test_single_editor_and_identity_metadata(self):
        scope = self.bind()
        process = scope.find_java()
        self.assertEqual((20, 42, os.getuid()), (process.pid, process.started, process.uid))
        scope.verify_process(process)
        self.assertEqual(self.path, scope.metadata()['path'])
        self.assertEqual(self.directory.stat().st_ino, scope.metadata()['inode'])

    def test_external_matching_process_is_not_inspected(self):
        self.process(30, scope='/other.scope')
        (self.proc / '30/status').unlink()
        self.assertEqual(20, self.bind().find_java().pid)

    def test_ambiguous_editors_fail(self):
        self.process(30)
        (self.directory / 'cgroup.procs').write_text('10\n20\n30\n')
        with self.assertRaisesRegex(RuntimeError, 'multiple'):
            self.bind().find_java()

    def test_moved_member_fails_without_reading_its_command(self):
        scope = self.bind()
        (self.proc / '20/cgroup').write_text('0::/other.scope\n')
        (self.proc / '20/cmdline').unlink()
        with self.assertRaisesRegex(RuntimeError, 'scope'):
            scope.find_java()

    def test_pid_reuse_and_uid_change_fail(self):
        for changes in ({'started': 43}, {'uid': os.getuid() + 1}):
            with self.subTest(changes=changes):
                self.process(20)
                scope = self.bind()
                process = scope.find_java()
                self.process(20, **changes)
                with self.assertRaises(RuntimeError):
                    scope.verify_process(process)

    def test_mid_read_reuse_fails(self):
        scope = self.bind()
        original = Path.read_bytes
        def changing(path):
            value = original(path)
            if path == self.proc / '20/cmdline':
                self.process(20, started=43)
            return value
        with patch.object(Path, 'read_bytes', changing):
            with self.assertRaisesRegex(RuntimeError, 'identity'):
                scope.find_java()

    def test_scope_replacement_and_boot_change_fail(self):
        scope = self.bind()
        self.directory.rename(self.directory.with_name('old.scope'))
        self.directory.mkdir()
        with self.assertRaisesRegex(RuntimeError, 'scope'):
            scope.find_java()
        (self.proc / 'sys/kernel/random/boot_id').write_text('22222222-2222-2222-2222-222222222222')
        with self.assertRaises(RuntimeError):
            scope.validate()

    def test_observer_move_reuse_or_unmanaged_scope_fail(self):
        scope = self.bind()
        self.process(10, started=99)
        with self.assertRaises(RuntimeError):
            scope.validate()
        self.process(10, scope='/unmanaged.scope')
        with self.assertRaisesRegex(RuntimeError, 'scope'):
            self.bind()

    def test_missing_or_unreadable_live_identity_is_not_skipped(self):
        (self.proc / '20/status').unlink()
        with self.assertRaises(RuntimeError):
            self.bind().find_java()

    def test_exit_before_attachment_waits_but_after_attachment_fails(self):
        scope = self.bind()
        process = scope.find_java()
        (self.directory / 'cgroup.procs').write_text('10\n')
        self.assertIsNone(scope.find_java())
        with self.assertRaises(RuntimeError):
            scope.verify_process(process)

    def test_descriptor_is_closed_and_cannot_be_reused(self):
        scope = self.bind()
        descriptor = scope.fd
        scope.close()
        scope.close()
        with self.assertRaises(OSError):
            os.fstat(descriptor)
        with self.assertRaises(RuntimeError):
            scope.validate()

    def test_no_management_entrypoints(self):
        for name in ('clean', 'send_verified', 'owned_processes', 'signal', 'subprocess'):
            self.assertFalse(hasattr(IDENTITY, name), name)


if __name__ == '__main__':
    unittest.main()
