#!/usr/bin/env python3
"""Bounded hook exec tests with synthetic observers only; never launches Cubism."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).parent


class MemoryHookTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.task = Path(self.temp.name) / 'task'
        self.home = self.task / 'turboism-home'
        self.evidence = self.task / 'evidence'
        self.evidence.mkdir(parents=True)
        (self.home / 'validation').mkdir(parents=True)
        self.script = self.home / 'validation/measure-task-memory.py'
        self.command = ['bash', str(ROOT / 'start-task-memory-observer.sh'), str(self.task),
                        str(self.home), str(self.evidence), *(['unused-test-context'] * 8),
                        str(Path(sys.executable).resolve())]
        self.env = {**os.environ, 'TURBOISM_HOST_VALIDATION_TASK_DIR': str(self.task)}

    def test_exec_preserves_pid_failure_and_isolated_interpreter(self):
        self.script.write_text('import os, sys, json\nprint(json.dumps(dict(pid=os.getpid(), isolated=sys.flags.isolated, noSite=sys.flags.no_site, noBytecode=sys.dont_write_bytecode)))\nraise SystemExit(7)\n')
        shadow = self.task / 'shadow'
        shadow.mkdir()
        (shadow / 'json.py').write_text('raise RuntimeError("unreviewed module loaded")\n')
        (shadow / 'sitecustomize.py').write_text('raise RuntimeError("site customization loaded")\n')
        self.env['PYTHONPATH'] = str(shadow)
        with subprocess.Popen(self.command, env=self.env, stdout=subprocess.PIPE, stderr=subprocess.PIPE) as child:
            out, error = child.communicate(timeout=5)
            self.assertEqual(7, child.returncode, (out, error))
            row = json.loads((self.evidence / 'memory-sampler.log').read_text())
            self.assertEqual(child.pid, row['pid'])
            self.assertEqual((1, 1, True), (row['isolated'], row['noSite'], row['noBytecode']))
        self.assertFalse((self.evidence / 'memory-sampler.pid').exists())

    def test_missing_frozen_helper_fails_no_legacy_fallback(self):
        shutil.copyfile(ROOT / 'measure-task-memory.py', self.script)
        result = subprocess.run(self.command, env=self.env, capture_output=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        log = (self.evidence / 'memory-sampler.log').read_text()
        self.assertIn('host_memory_identity.py', log)
        self.assertIn('FileNotFoundError', log)
        self.assertFalse((self.evidence / 'memory-samples.jsonl').exists())

    def test_wrong_context_rejected_before_observer(self):
        self.script.write_text('raise RuntimeError("should never execute")\n')
        for command, env in ((self.command[:-1], self.env), (self.command, {**self.env, 'TURBOISM_HOST_VALIDATION_TASK_DIR': 'other'})):
            with self.subTest(command=command):
                result = subprocess.run(command, env=env, capture_output=True, timeout=5)
                self.assertEqual(2, result.returncode)
                self.assertFalse((self.evidence / 'memory-sampler.log').exists())


if __name__ == '__main__':
    unittest.main()
