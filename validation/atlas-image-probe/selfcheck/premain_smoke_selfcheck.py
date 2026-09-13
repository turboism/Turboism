#!/usr/bin/env python3
"""Validator regression with a fake JVM protocol; no Java or host is executed."""
import contextlib
import hashlib
import importlib.util
import io
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / 'premain-smoke.py'
spec = importlib.util.spec_from_file_location('premain_smoke', SCRIPT)
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class SmokeValidationTest(unittest.TestCase):
    def exercise(self, fault):
        with tempfile.TemporaryDirectory(prefix='atlas-smoke-validator-') as directory:
            root = Path(directory)
            editor = root / 'editor.jar'
            agent = root / 'agent.jar'
            editor.write_bytes(b'synthetic identity input, not a host JAR')
            agent.write_bytes(b'not executed')
            output = root / 'output'
            calls = 0

            def fake_jvm(command, **kwargs):
                nonlocal calls
                calls += 1
                self.assertEqual(command[-1], '-version')
                run = output / 'premain-smoke'
                if calls == 1:
                    run.mkdir()
                    identity = {'version': '5303', 'editorSha256': hashlib.sha256(editor.read_bytes()).hexdigest()}
                    result = {'status': 'BLOCKED', 'eventCount': '0', 'runId': 'premain-smoke',
                              'actual.requiredTargetObserved': 'false', 'optimizationReadiness': 'NOT_EVALUATED',
                              'completionReason': 'JVM_SHUTDOWN', 'reportAttempts': '1'}
                    if fault == 'wrong-status':
                        result['status'] = 'PASS'
                    if fault == 'wrong-identity':
                        identity['version'] = 'wrong'
                    for name, values in [('identity', identity), ('result', result)]:
                        (run / (name + '.properties')).write_text(''.join(k + '=' + v + '\n' for k, v in values.items()))
                    marker = b'ATLAS_IMAGE_LOAD_PROBE_ARMED\n'
                    if fault == 'not-armed':
                        marker = b'ATLAS_IMAGE_LOAD_PROBE_BLOCKED\n'
                    return subprocess.CompletedProcess(command, 7 if fault == 'exit-error' else 0, marker)
                marker = b'ATLAS_IMAGE_LOAD_PROBE_BLOCKED\n'
                if fault == 'duplicate-accepted':
                    marker = b'ATLAS_IMAGE_LOAD_PROBE_ARMED\n'
                if fault == 'duplicate-overwrite':
                    (run / 'result.properties').write_text('status=PASS\n')
                if fault == 'jar-mutated':
                    editor.write_bytes(b'changed')
                return subprocess.CompletedProcess(command, 0, marker)

            argv = [str(SCRIPT), '--java', 'fake-java', '--agent', str(agent), '--editor-jar', str(editor),
                    '--version', '5303', '--output', str(output)]
            console = io.StringIO()
            with patch.object(sys, 'argv', argv), patch.object(smoke.subprocess, 'run', side_effect=fake_jvm), contextlib.redirect_stdout(console):
                if fault is None:
                    smoke.main()
                    self.assertIn('PREMAIN_READ_ONLY_SMOKE PASS', console.getvalue())
                    self.assertEqual(calls, 2)
                else:
                    with self.assertRaises(RuntimeError):
                        smoke.main()
                    self.assertNotIn('PREMAIN_READ_ONLY_SMOKE PASS', console.getvalue())

    def test_valid_protocol(self):
        self.exercise(None)

    def test_faults_never_report_pass(self):
        for fault in ('wrong-status', 'wrong-identity', 'not-armed', 'exit-error',
                      'duplicate-accepted', 'duplicate-overwrite', 'jar-mutated'):
            with self.subTest(fault=fault):
                self.exercise(fault)


if __name__ == '__main__':
    unittest.main()
