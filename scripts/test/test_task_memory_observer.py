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
    def test_memory_units_and_required_fields(self):
        self.assertEqual({'Rss': 2048, 'Pss': 1024}, MODULE.kilobytes('Rss: 2 kB\nPss: 1 kB\n', ('Rss', 'Pss')))
        for text in ('Rss: 2 kB\n', 'Rss: -1 kB\nPss: 1 kB\n', 'Rss: 2 MB\nPss: 1 kB\n'):
            with self.assertRaises(ValueError):
                MODULE.kilobytes(text, ('Rss', 'Pss'))

    def test_identity_changes_are_not_attributed_to_original_process(self):
        before = MODULE.IDENTITY.Process(10, 1, 42, os.getuid(), 'S', (), ())
        MODULE.same_process(before, before)
        for after in (None, MODULE.IDENTITY.Process(10, 1, 43, os.getuid(), 'S', (), ())):
            with self.assertRaises(RuntimeError):
                MODULE.same_process(before, after)

    def test_real_self_memory_sample_without_signals(self):
        process = MODULE.IDENTITY.read_process(os.getpid())
        result = MODULE.sample(process)
        self.assertGreater(result['rollup']['Rss'], 0)
        self.assertGreater(result['rollup']['Pss'], 0)
        self.assertEqual(os.getpid(), result['pid'])

    def test_changed_identity_during_read_fails(self):
        before = MODULE.IDENTITY.read_process(os.getpid())
        changed = MODULE.IDENTITY.Process(before.pid, before.parent, before.started + 1, before.uid, 'S', (), ())
        with mock.patch.object(MODULE.IDENTITY, 'read_process', side_effect=[before, changed]):
            with self.assertRaises(RuntimeError):
                MODULE.sample(before)

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
            child = subprocess.Popen(command[:1] + ['-Dturboism.validation.textureUpload.memoryIdleSeconds=30'] + command[1:], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
            deadline = time.monotonic() + 40
            while not (state / 'end.properties').exists() and child.poll() is None and time.monotonic() < deadline:
                time.sleep(0.1)
            (state / 'complete.properties').write_text('status=PASS\n')
            out, error = child.communicate(timeout=20)
            self.assertEqual(0, child.returncode, out + error)
            self.assertTrue((state / 'ready.properties').is_file())
            self.assertTrue((state / 'end.properties').is_file())
            self.assertEqual(32, len((state / 'jvm.csv').read_text().splitlines()))


if __name__ == '__main__':
    unittest.main()
