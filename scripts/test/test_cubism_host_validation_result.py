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
COUNT_BODY = RUNNER.read_text().split('result_file_assertion_count() {', 1)[1].split("<<'PY'\n", 1)[1].split('\nPY\n', 1)[0]

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


class AssertionCountBody(unittest.TestCase):
    """Runs the runner's real assertion-count reader without a host launch."""

    def count(self, payload):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'result.properties'
            path.write_text(payload)
            return subprocess.run([sys.executable, '-c', COUNT_BODY, str(path)],
                                  text=True, capture_output=True)

    def test_verdict_count_wins_over_total(self):
        result = self.count('assertions=9\nassertions.verdict=2\n')
        self.assertEqual(0, result.returncode)
        self.assertEqual('2', result.stdout.strip())

    def test_total_is_the_fallback(self):
        result = self.count('assertions=5\n')
        self.assertEqual(0, result.returncode)
        self.assertEqual('5', result.stdout.strip())

    def test_info_only_reports_zero_verdicts(self):
        result = self.count('assertions=3\nassertions.verdict=0\n')
        self.assertEqual(0, result.returncode)
        self.assertEqual('0', result.stdout.strip())

    def test_missing_count_exits_non_zero(self):
        result = self.count('status=PASS\n')
        self.assertEqual(1, result.returncode)
        self.assertEqual('', result.stdout.strip())

    def test_malformed_count_reports_minus_one(self):
        result = self.count('assertions=many\n')
        self.assertEqual(0, result.returncode)
        self.assertEqual('-1', result.stdout.strip())


class TerminalPublicationRace(unittest.TestCase):
    """Exercise the runner's actual polling helpers with task-local evidence only."""

    def poll(self, payload, marker=False, preexisting=False, fail_lines=None,
             require_assertions=False, pass_line='status=PASS'):
        source = RUNNER.read_text()
        functions = source.split('terminal_result_observed() {', 1)[1].split('\nresult_passed=0', 1)[0]
        functions = 'terminal_result_observed() {' + functions
        with tempfile.TemporaryDirectory() as directory:
            evidence = Path(directory) / 'terminal'
            if preexisting:
                evidence.write_text(payload)
            env = dict(os.environ, TEST_RESULT_PATH=str(evidence), TEST_PAYLOAD=payload,
                       TEST_FAIL_LINES=" ".join(fail_lines or ["status=FAIL"]),
                       TEST_PASS_LINE=pass_line)
            script = """
set -eu
log_file=$TEST_RESULT_PATH
result_file=$TEST_RESULT_PATH
result_marker=''
result_pass_line=$TEST_PASS_LINE
result_fail_lines=(${TEST_FAIL_LINES:-'status=FAIL'})
require_assertions=0
failure_markers=()
fail() { printf '%s\n' "$1" >&2; exit 23; }
latest_runtime_log() { printf '%s' "$TEST_RESULT_PATH"; }
result_file_contains() { [ -f "$1" ] && grep -Fxq "$2" "$1"; }
result_file_assertion_count() { python3 - "$TEST_RESULT_PATH" <<'PY'
""" + COUNT_BODY + """
PY
}
runtime_log_contains() { result_file_contains "$@"; }
remote_process_alive() { printf '%s\n' "$TEST_PAYLOAD" > "$TEST_RESULT_PATH"; return 1; }
"""
            if marker:
                script += "result_marker='terminal=PASS'\n"
            if require_assertions:
                script += "require_assertions=1\n"
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

    def test_any_configured_fail_line_matches(self):
        # The wrapper passes one fail line per non-pass terminal state, so BLOCKED and
        # SKIPPED runs fail even when the result file carries no `status=FAIL` line.
        for payload in ('status=BLOCKED', 'status=SKIPPED'):
            with self.subTest(payload=payload):
                result = self.poll(payload,
                    fail_lines=['status=FAIL', 'status=BLOCKED', 'status=SKIPPED'])
                self.assertEqual(23, result.returncode)
                self.assertIn('result file reported failure', result.stderr)

    def test_required_assertions_reject_a_result_without_count(self):
        result = self.poll('status=PASS', require_assertions=True)
        self.assertEqual(23, result.returncode)
        self.assertIn('publishes no assertion count', result.stderr)

    def test_required_assertions_reject_a_zero_count(self):
        result = self.poll('status=PASS\nassertions=0\n', require_assertions=True)
        self.assertEqual(23, result.returncode)
        self.assertIn('publishes no verdict assertion', result.stderr)
        self.assertIn('count=0', result.stderr)

    def test_info_only_counts_do_not_pass_the_first_screen(self):
        # The verdict count decides, so three INFO records with zero verdicts fail even
        # though the total counter is positive.
        payload = 'status=PASS\nassertions=3\nassertions.verdict=0\n'
        for require in (False, True):
            with self.subTest(require_assertions=require):
                result = self.poll(payload, require_assertions=require)
                self.assertEqual(23, result.returncode)
                self.assertIn('publishes no verdict assertion', result.stderr)
                self.assertIn('count=0', result.stderr)

    def test_positive_verdict_count_passes_with_required_assertions(self):
        result = self.poll('status=PASS\nassertions=4\nassertions.verdict=4\n',
                           require_assertions=True)
        self.assertEqual(0, result.returncode)

    def test_preexisting_pass_does_not_need_process_probe(self):
        self.assertEqual(0, self.poll('status=PASS', preexisting=True).returncode)

if __name__ == '__main__':
    unittest.main()
