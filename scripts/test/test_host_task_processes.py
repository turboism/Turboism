#!/usr/bin/env python3
"""Task identity/cleanup regressions; signals only a test-owned sleep process."""
import importlib.util
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

FILE = Path(__file__).resolve().parents[1] / 'preview/host-task-processes.py'
SPEC = importlib.util.spec_from_file_location('host_task_processes', FILE)
MODULE = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)

class TaskProcesses(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.task = self.root / 'task'
        self.proc = self.root / 'proc'
        self.proc.mkdir()

    def tearDown(self):
        self.temporary.cleanup()

    def process(self, pid, parent=1, args=(), env=(), state='S', uid=1000, started=42):
        p = self.proc / str(pid)
        p.mkdir()
        fields = [state, str(parent)] + ['0'] * 17 + [str(started)]
        (p / 'stat').write_text(f'{pid} (test process) ' + ' '.join(fields))
        (p / 'status').write_text(f'Uid:\t{uid}\t{uid}\t{uid}\t{uid}\n')
        (p / 'cmdline').write_bytes(b'\0'.join(os.fsencode(x) for x in args) + b'\0')
        (p / 'environ').write_bytes(b'\0'.join(os.fsencode(x) for x in env) + b'\0')

    def test_trailing_slash_orphan_and_steam_prefix_are_owned(self):
        self.process(10, env=[f'WINEPREFIX={self.task}/prefix/pfx/'])
        self.process(11, env=[f'STEAM_COMPAT_DATA_PATH={self.task}/prefix'])
        self.process(12, env=[f'WINEPREFIX={self.task}/prefix/pfx-other'])
        found = MODULE.owned_processes(self.task, self.proc, self_pid=999, uid=1000)
        self.assertEqual({10, 11}, set(found))

    def test_wrapper_descendants_but_never_coordinator_command_substrings(self):
        self.process(10, args=['bash', str(self.task / 'launch.sh')])
        self.process(11, parent=10, args=['child'])
        self.process(12, parent=11, args=['grandchild'])
        self.process(20, args=['bash', '-c', f'echo {self.task}'])
        self.process(21, args=['python3', str(self.task / 'notes.txt')])
        self.assertEqual({10, 11, 12}, set(MODULE.owned_processes(self.task, self.proc, self_pid=999, uid=1000)))

    def test_self_ancestors_other_users_and_zombies_are_excluded(self):
        self.process(1, parent=0, uid=0)
        self.process(100, env=[f'WINEPREFIX={self.task}/prefix/pfx'])
        self.process(200, parent=100, env=[f'WINEPREFIX={self.task}/prefix/pfx'])
        self.process(10, env=[f'WINEPREFIX={self.task}/prefix/pfx'], uid=2000)
        self.process(11, env=[f'WINEPREFIX={self.task}/prefix/pfx'], state='Z')
        self.assertEqual({}, MODULE.owned_processes(self.task, self.proc, self_pid=200, uid=1000))

    def test_preexisting_user_services_are_excluded_before_environment_reads(self):
        record = self.task / 'evidence/identity-before.properties'
        record.parent.mkdir(parents=True)
        record.write_text('taskId=task\n')
        os.utime(record, (100, 100))
        (self.proc / 'stat').write_text('btime 0\n')
        ticks = os.sysconf('SC_CLK_TCK')
        self.process(10, started=90*ticks)
        self.process(20, started=101*ticks, env=[f'WINEPREFIX={self.task}/prefix/pfx/'])
        original = MODULE.read_process
        def guarded(pid, proc, read_environment=True):
            if pid == 10 and read_environment:
                raise AssertionError('must not inspect protected services predating the task')
            return original(pid, proc, read_environment)
        with mock.patch.object(MODULE, 'read_process', side_effect=guarded):
            self.assertEqual({20}, set(MODULE.owned_processes(self.task, self.proc, self_pid=999, uid=1000)))

    def test_stat_parser_handles_spaces_in_process_name(self):
        self.process(10, parent=7, started=123456)
        p = MODULE.read_process(10, self.proc)
        self.assertEqual((7, 123456, 1000), (p.parent, p.started, p.uid))

    def test_pid_reuse_is_blocked_before_signal(self):
        before = MODULE.Process(42, 1, 100, 1000, 'S', (), ())
        after = MODULE.Process(42, 1, 101, 1000, 'S', (), ())
        with mock.patch.object(MODULE.os, 'pidfd_open', return_value=900), mock.patch.object(MODULE.os, 'close'), \
             mock.patch.object(MODULE, 'read_process', return_value=after), mock.patch.object(MODULE.signal, 'pidfd_send_signal') as send:
            with self.assertRaisesRegex(RuntimeError, 'identity changed'):
                MODULE.send_verified(before, signal.SIGTERM)
            send.assert_not_called()

    def test_bad_artifact_identity_never_opens_pidfd(self):
        with mock.patch.object(MODULE, 'verify_task', side_effect=RuntimeError('identity changed')), \
             mock.patch.object(MODULE.os, 'pidfd_open') as open_pid:
            with self.assertRaises(RuntimeError):
                MODULE.clean(self.task)
            open_pid.assert_not_called()

    def test_real_nondumpable_zombie_needs_no_environment_or_signal(self):
        child = subprocess.Popen([sys.executable, '-c',
            'import ctypes; assert ctypes.CDLL(None).prctl(4, 0, 0, 0, 0) == 0'])
        try:
            os.waitid(os.P_PID, child.pid, os.WEXITED | os.WNOWAIT)
            stat = (Path('/proc') / str(child.pid) / 'stat').read_text().rsplit(')', 1)[1].split()
            self.assertEqual('Z', stat[0])
            with mock.patch.object(MODULE.signal, 'pidfd_send_signal') as send:
                self.assertIsNone(MODULE.read_process(child.pid))
                send.assert_not_called()
        finally:
            child.wait(timeout=5)

    def test_environment_denial_after_exit_is_rechecked_but_live_denial_stays_blocked(self):
        for next_state, next_start, next_uid, allowed in [
                ('Z', 42, os.getuid(), True), ('S', 42, os.getuid(), False),
                ('Z', 43, os.getuid(), False), ('Z', 42, os.getuid() + 1, False)]:
            with self.subTest(state=next_state, started=next_start, uid=next_uid):
                pid = 10
                directory = self.proc / str(pid)
                if directory.exists():
                    for file in directory.iterdir():
                        file.unlink()
                    directory.rmdir()
                self.process(pid, uid=os.getuid())
                original = Path.read_bytes
                def denied(path):
                    if path == directory / 'environ':
                        fields = [next_state, '1'] + ['0'] * 17 + [str(next_start)]
                        (directory / 'stat').write_text('10 (test process) ' + ' '.join(fields))
                        (directory / 'status').write_text(f'Uid:\t{next_uid}\t{next_uid}\t{next_uid}\t{next_uid}\n')
                        raise PermissionError(13, 'denied')
                    return original(path)
                with mock.patch.object(Path, 'read_bytes', denied), \
                     mock.patch.object(MODULE.signal, 'pidfd_send_signal') as send:
                    if allowed:
                        self.assertIsNone(MODULE.read_process(pid, self.proc))
                    else:
                        with self.assertRaises(RuntimeError):
                            MODULE.read_process(pid, self.proc)
                    send.assert_not_called()

    def test_process_disappearing_after_denial_needs_no_signal(self):
        self.process(10, uid=os.getuid())
        original = Path.read_bytes
        def vanished(path):
            if path == self.proc / '10/environ':
                for file in path.parent.iterdir():
                    file.unlink()
                path.parent.rmdir()
                raise PermissionError(13, 'exited during read')
            return original(path)
        with mock.patch.object(Path, 'read_bytes', vanished), \
             mock.patch.object(MODULE.signal, 'pidfd_send_signal') as send:
            self.assertIsNone(MODULE.read_process(10, self.proc))
            send.assert_not_called()

    def test_pidfd_signals_only_test_owned_child(self):
        child = subprocess.Popen(['sleep', '60'])
        try:
            process = MODULE.read_process(child.pid)
            self.assertIsNotNone(process)
            MODULE.send_verified(process, signal.SIGTERM)
            child.wait(timeout=5)
            self.assertEqual(-signal.SIGTERM, child.returncode)
        finally:
            if child.poll() is None:
                child.terminate()
                child.wait(timeout=5)

if __name__ == '__main__':
    unittest.main()
