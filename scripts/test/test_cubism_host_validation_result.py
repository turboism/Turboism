#!/usr/bin/env python3
"""Execute the runner's real result-file matcher, without any host launch."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

RUNNER = Path(__file__).resolve().parents[1] / 'preview/run-cubism-host-validation.sh'
BODY = RUNNER.read_text().split('result_file_contains() {', 1)[1].split("<<'REMOTE'\n", 1)[1].split('\nREMOTE\n', 1)[0]

class ResultLines(unittest.TestCase):
    def matches(self, payload, expected='status=PASS'):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'result with spaces.properties'
            if payload is not None:
                path.write_bytes(payload)
            env = dict(os.environ, TEST_RESULT_PATH=str(path), TEST_EXPECTED=expected)
            script = 'remote_args() { REMOTE_ARGS=("$TEST_RESULT_PATH" "$TEST_EXPECTED"); }\n' + BODY
            return subprocess.run(['bash'], input=script, text=True, env=env, capture_output=True).returncode == 0

    def test_lf(self):
        self.assertTrue(self.matches(b'other=1\nstatus=PASS\n'))

    def test_windows_properties(self):
        self.assertTrue(self.matches(b'# Windows Properties.store\r\nstatus=PASS\r\n'))
        self.assertTrue(self.matches(b'status=FAIL\r\n', 'status=FAIL'))

    def test_exact_only(self):
        for content in [b'status=PASS extra\n', b' status=PASS\n', b'status=PASS \r\n',
                        b'status=PA\rSS\n', b'status=PASS\r\r\n', b'prefixstatus=PASS\n', b'']:
            with self.subTest(content=content):
                self.assertFalse(self.matches(content))

    def test_missing(self):
        self.assertFalse(self.matches(None))

if __name__ == '__main__':
    unittest.main()
