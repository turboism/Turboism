#!/usr/bin/env python3
"""Execute the runner's real result-file matcher, without any host launch."""
import os
from pathlib import Path
import subprocess
import tempfile
import sys
import unittest

RUNNER = Path(__file__).resolve().parents[1] / 'preview/run-cubism-host-validation.sh'
BODY = RUNNER.read_text().split('result_file_contains() {', 1)[1].split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]

class ResultLines(unittest.TestCase):
    def matches(self, payload, expected='status=PASS'):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'result with spaces.properties'
            if payload is not None:
                path.write_bytes(payload)
            return subprocess.run([sys.executable, "-c", BODY, str(path), expected],
                                  text=True, capture_output=True).returncode == 0

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


class TerminalPublicationRace(unittest.TestCase):
    """Exercise the runner's actual polling helpers with task-local evidence only."""

    def poll(self, payload, marker=False, preexisting=False):
        source = RUNNER.read_text()
        functions = source.split('terminal_result_observed() {', 1)[1].split('\nresult_passed=0', 1)[0]
        functions = 'terminal_result_observed() {' + functions
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / 'terminal'
            if preexisting:
                evidence.write_text(payload)
            env = dict(os.environ, TEST_RESULT_PATH=str(evidence), TEST_PAYLOAD=payload)
            script = """
set -eu
log_file=$TEST_RESULT_PATH
result_file=$TEST_RESULT_PATH
result_marker=''
result_pass_line='status=PASS'
result_fail_line='status=FAIL'
failure_markers=()
fail() { printf '%s\n' "$1" >&2; exit 23; }
latest_runtime_log() { printf '%s' "$TEST_RESULT_PATH"; }
result_file_contains() { [ -f "$1" ] && grep -Fxq "$2" "$1"; }
runtime_log_contains() { result_file_contains "$@"; }
remote_process_alive() { printf '%s\n' "$TEST_PAYLOAD" > "$TEST_RESULT_PATH"; return 1; }
"""
            if marker:
                script += "result_marker='terminal=PASS'\n"
            script += functions + '\nterminal_result_or_process_exit\n'
            return subprocess.run(['bash'], input=script, text=True, env=env, capture_output=True)

    def test_result_published_between_first_read_and_process_death_is_accepted(self):
        self.assertEqual(0, self.poll('status=PASS').returncode)

    def test_marker_published_between_first_read_and_process_death_is_accepted(self):
        self.assertEqual(0, self.poll('terminal=PASS', marker=True).returncode)

    def test_failure_published_on_death_still_fails(self):
        result = self.poll('status=FAIL')
        self.assertEqual(23, result.returncode)
        self.assertIn('result file reported failure', result.stderr)

    def test_dead_process_without_result_is_not_a_pass(self):
        result = self.poll('unfinished=1')
        self.assertEqual(23, result.returncode)
        self.assertIn('host exited before terminal result', result.stderr)

    def test_preexisting_pass_does_not_need_process_probe(self):
        self.assertEqual(0, self.poll('status=PASS', preexisting=True).returncode)

if __name__ == '__main__':
    unittest.main()
