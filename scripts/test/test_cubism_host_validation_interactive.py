#!/usr/bin/env python3
"""Offline separation of interactive sessions from validation, including safe coordinator interruption."""
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / 'scripts/preview/run-cubism-host-validation.sh'

class InteractiveMode(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        (self.root / 'agent.jar').write_text('dry-run fixture only')
        (self.root / 'model.cmo3').write_text('dry-run fixture only')
        self.env = dict(os.environ, TURBOISM_ENV_FILE=str(self.root / 'missing.env'))
        self.base = ['bash', str(RUNNER), '--name', 'interactive-contract', '--version', '5302',
                     '--transport', 'local', '--bundle-root', str(self.root), '--agent', str(self.root / 'agent.jar'),
                     '--fixture-local', str(self.root / 'model.cmo3'), '--golden-prefix', str(self.root / 'golden'),
                     '--remote-root', str(self.root / 'tasks'), '--proton-runner', str(self.root / 'proton'), '--dry-run']

    def tearDown(self):
        self.temporary.cleanup()

    def invoke(self, *arguments):
        return subprocess.run(self.base + list(arguments), env=self.env, text=True, capture_output=True)

    def test_interactive_needs_no_validation_agent_and_keeps_work(self):
        result = self.invoke('--interactive')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn('interactive=1\n', result.stdout)
        self.assertIn('keepPrefix=1\n', result.stdout)
        self.assertIn('auxAgentCount=0\n', result.stdout)
        self.assertIn('resultFile=\n', result.stdout)
        self.assertFalse((self.root / 'tasks').exists())

    def test_interactive_cannot_masquerade_as_validation(self):
        for args in [('--result-file', 'state/result.txt'), ('--result-marker', 'PASS'),
                     ('--trigger', 'state/go.flag'), ('--aux-agent', str(self.root / 'agent.jar'))]:
            with self.subTest(args=args):
                result = self.invoke('--interactive', *args)
                self.assertNotEqual(0, result.returncode)
                self.assertIn('interactive mode cannot', result.stderr)

    def test_default_validation_still_requires_probe_and_result(self):
        self.assertNotEqual(0, self.invoke('--result-marker', 'PASS').returncode)
        self.assertNotEqual(0, self.invoke('--aux-agent', str(self.root / 'agent.jar')).returncode)

    def test_interrupted_interactive_coordinator_does_not_stop_editor(self):
        source = RUNNER.read_text()
        body = 'on_exit() {' + source.split('on_exit() {', 1)[1].split('\ntrap on_exit EXIT', 1)[0]
        for interactive in (0, 1):
            with self.subTest(interactive=interactive):
                script = '''
set +e
remote_stop_process_tree() { echo STOP; }
run_remote_hook() { echo HOOK; }
collect_evidence() { :; }
cleanup_prefix() { :; }
launched=1; success=0; wrapper_cleanup_done=0
remote_pre_cleanup=''; task_id=owned-task; task_dir=/unused/task; local_evidence_dir=/unused/evidence
local_tmp="$(mktemp -d)"
''' + f'\ninteractive={interactive}\n' + body + '\nfalse\non_exit\n'
                result = subprocess.run(['bash'], input=script, text=True, capture_output=True)
                if interactive:
                    self.assertNotIn('STOP', result.stdout)
                    self.assertNotIn('HOOK', result.stdout)
                    self.assertIn('SESSION_DETACHED', result.stderr)
                else:
                    self.assertIn('STOP', result.stdout)
                    self.assertIn('HOOK', result.stdout)

    def exit_loop(self, interactive=0, timeout=False, exit_code='0'):
        source = RUNNER.read_text()
        body = 'deadline=$((SECONDS + exit_timeout))' + source.split(
            'deadline=$((SECONDS + exit_timeout))', 1)[1].split(
            'if [ -n "$cubism_java_console_marker" ]; then', 1)[0]
        script = '''
set -eu
polls=0; wrapper_cleanup_done=0; exit_timeout=1; poll_seconds=0.01
ssh_host=unused; evidence_dir=/unused
ssh_cmd=(fake_remote)
log() { :; }
fail() { echo "$*" >&2; exit 1; }
remote_normal_exit_evidence_seen() { return 0; }
remote_record_wrapper_cleanup() { echo RECORDED; }
remote_stop_process_tree() { echo STOP; }
remote_process_alive() { polls=$((polls + 1)); [ "$always_alive" = 1 ] || [ "$polls" -le 3 ]; }
fake_remote() { case "$2" in cat*) printf '%s' "$exit_code";; *) :;; esac; }
''' + f'\ninteractive={interactive}; always_alive={int(timeout)}; exit_code={exit_code}\n' + body
        return subprocess.run(['bash'], input=script, text=True, capture_output=True, timeout=5)

    def test_shutdown_log_does_not_terminate_still_exiting_launcher(self):
        for interactive in (0, 1):
            with self.subTest(interactive=interactive):
                result = self.exit_loop(interactive=interactive)
                self.assertEqual(0, result.returncode, result.stderr)
                self.assertNotIn('STOP', result.stdout)
                self.assertNotIn('RECORDED', result.stdout)

    def test_forced_cleanup_after_exit_timeout_is_not_validation_pass(self):
        result = self.exit_loop(timeout=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn('STOP', result.stdout)
        self.assertIn('graceful', result.stderr)

    def test_nonzero_launcher_exit_is_never_accepted_by_shutdown_marker(self):
        result = self.exit_loop(exit_code='1')
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn('STOP', result.stdout)

if __name__ == '__main__':
    unittest.main()
