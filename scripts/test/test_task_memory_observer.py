#!/usr/bin/env python3
"""Memory parsing/coverage/identity tests; real reads only target this test process."""
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location('task_memory_observer', Path(__file__).with_name('measure-task-memory.py'))
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


class MemoryObserver(unittest.TestCase):
    def self_process(self):
        # Real metric reads target only this unittest PID; admission itself is
        # covered with fake cgroup fixtures, not bypassed in production.
        fields = Path('/proc/self/stat').read_text().rsplit(')', 1)[1].split()
        return MODULE.IDENTITY.Process(os.getpid(), int(fields[19]), os.getuid(), ())

    def self_scope(self):
        scope = mock.Mock(proc=Path('/proc'))
        process = self.self_process()
        scope.read_process.side_effect = lambda pid: self.self_process() if pid == os.getpid() else None
        scope.verify_process.side_effect = lambda expected: MODULE.same_process(expected, scope.read_process(expected.pid))
        scope.metadata.return_value = {'testOnly': True}
        scope.find_java.return_value = process
        return process, scope
    def test_memory_units_and_required_fields(self):
        self.assertEqual({'Rss': 2048, 'Pss': 1024}, MODULE.kilobytes('Rss: 2 kB\nPss: 1 kB\n', ('Rss', 'Pss')))
        for text in ('Rss: 2 kB\n', 'Rss: -1 kB\nPss: 1 kB\n', 'Rss: 2 MB\nPss: 1 kB\n'):
            with self.assertRaises(ValueError):
                MODULE.kilobytes(text, ('Rss', 'Pss'))

    def test_identity_changes_are_not_attributed_to_original_process(self):
        before = MODULE.IDENTITY.Process(10, 42, os.getuid(), ())
        MODULE.same_process(before, before)
        for after in (None, MODULE.IDENTITY.Process(10, 43, os.getuid(), ())):
            with self.assertRaises(RuntimeError):
                MODULE.same_process(before, after)

    def test_real_self_memory_sample_without_signals(self):
        process, scope = self.self_scope()
        result = MODULE.sample(process, scope)
        self.assertGreater(result['rollup']['Rss'], 0)
        self.assertGreater(result['rollup']['Pss'], 0)
        self.assertEqual(os.getpid(), result['pid'])

    def test_changed_identity_during_read_fails(self):
        before, scope = self.self_scope()
        changed = MODULE.IDENTITY.Process(before.pid, before.started + 1, before.uid, ())
        scope.read_process.side_effect = [before, changed]
        with self.assertRaises(RuntimeError):
            MODULE.sample(before, scope)

    def test_scope_failure_publishes_fail_without_sampling(self):
        with tempfile.TemporaryDirectory() as name:
            task = Path(name)
            (task / 'evidence').mkdir()
            with mock.patch.object(MODULE.IDENTITY, 'verify_task'), \
                    mock.patch.object(MODULE.IDENTITY, 'BoundScope', side_effect=RuntimeError('scope mismatch')):
                with self.assertRaisesRegex(RuntimeError, 'scope mismatch'):
                    MODULE.measure(task)
            complete = task / 'turboism-home/state/texture-upload/memory/complete.properties'
            self.assertIn('status=FAIL', complete.read_text())
            self.assertFalse((task / 'evidence/memory-samples.jsonl').exists())

    def test_sampling_failure_closes_scope(self):
        with tempfile.TemporaryDirectory() as name:
            task = Path(name)
            (task / 'evidence').mkdir()
            scope = mock.Mock()
            scope.find_java.side_effect = RuntimeError('identity mismatch')
            with mock.patch.object(MODULE.IDENTITY, 'verify_task'), \
                    mock.patch.object(MODULE.IDENTITY, 'BoundScope', return_value=scope):
                with self.assertRaisesRegex(RuntimeError, 'identity mismatch'):
                    MODULE.measure(task)
            scope.close.assert_called_once()

    def test_full_idle_window_required(self):
        rows = [{'epochMillis': x * 1000, 'monotonicNs': x * 1_000_000_000} for x in range(33)]
        ready, end = {'epochMillis': '1000', 'seconds': '30'}, {'epochMillis': '31000'}
        MODULE.validate_window(rows, ready, end)
        for partial in (rows[2:], rows[:30], rows[::6]):
            with self.assertRaises(RuntimeError):
                MODULE.validate_window(partial, ready, end)

    def test_existing_evidence_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as name:
            path = Path(name) / 'complete.properties'
            MODULE.publish(path, 'status=FAIL\n')
            with self.assertRaises(RuntimeError):
                MODULE.publish(path, 'status=PASS\n')
            self.assertEqual('status=FAIL\n', path.read_text())

    def test_jdk_helper_default_and_complete_idle_handshake(self):
        with tempfile.TemporaryDirectory() as name:
            root = Path(name)
            driver = root / 'MemoryDriver.java'
            driver.write_text('''package dev.turboism.validation;
public class MemoryDriver {
  public static void main(String[] args) throws Exception {
    NativeMemoryObservation.startLoading(java.nio.file.Path.of(args[0]));
    NativeMemoryObservation.observe(java.nio.file.Path.of(args[0]));
  }
}''')
            source = ROOT / 'testing/host-validation/image-archive/src/dev/turboism/validation/NativeMemoryObservation.java'
            subprocess.run(['javac', '--release', '17', '-d', str(root), str(source), str(driver)], check=True, capture_output=True)
            command = ['java', '-cp', str(root), 'dev.turboism.validation.MemoryDriver', str(root / 'home')]
            subprocess.run(command, check=True, capture_output=True)
            self.assertFalse((root / 'home').exists())
            state = root / 'home/state/texture-upload/memory'
            state.mkdir(parents=True)
            (state / 'attached.properties').write_text('pid=test\n')
            child = subprocess.Popen(command[:1] + ['-Dturboism.validation.textureUpload.memoryIdleSeconds=30', '-Dturboism.validation.textureUpload.loadingTrace=true'] + command[1:], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            deadline = time.monotonic() + 40
            while not (state / 'end.properties').exists() and child.poll() is None and time.monotonic() < deadline:
                time.sleep(0.1)
            (state / 'complete.properties').write_text('status=PASS\n')
            out, error = child.communicate(timeout=20)
            self.assertEqual(0, child.returncode, out + error)
            self.assertTrue((state / 'ready.properties').is_file())
            self.assertTrue((state / 'end.properties').is_file())
            self.assertEqual(32, len((state / 'jvm.csv').read_text().splitlines()))
            self.assertGreaterEqual(len((state / 'jvm-loading.csv').read_text().splitlines()), 2)


if __name__ == '__main__':
    unittest.main()
