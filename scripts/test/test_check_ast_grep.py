#!/usr/bin/env python3
"""Stdlib runnable suite for scripts/check_ast_grep.py.

Run:  python3 scripts/test/test_check_ast_grep.py

`checkArchitectureRules` is only a real gate while the pinned CLI is the one that
runs: an unpinned global install, an unrelated tool answering `--version`, a
failed version probe, or a missing binary must fail the build instead of
silently turning the AST guardrails off.

Each case runs the real script with an isolated `PATH` containing only generated
stand-ins, so no developer-installed ast-grep can satisfy the gate by accident.
"""

import json
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
CHECKER = SCRIPTS_DIR / "check_ast_grep.py"
PINNED = "0.45.3"

FAKE_BINARY = '''#!{python}
import json, os, sys
argv = sys.argv[1:]
with open(os.environ["FAKE_AST_GREP_RECORD"], "a", encoding="utf-8") as handle:
    handle.write(json.dumps([os.path.basename(sys.argv[0]), *argv]) + "\\n")
if argv and argv[0] == "--version":
    sys.stderr.write({warning!r})
    sys.stdout.write({version!r})
    sys.exit({version_exit})
if argv and argv[0] == "test":
    sys.exit({test_exit})
if argv and argv[0] == "scan":
    sys.exit({scan_exit})
sys.exit(0)
'''


class FakePath:
    """Isolated PATH plus generated ast-grep stand-ins."""

    def __init__(self):
        self.root = Path(tempfile.mkdtemp(prefix="ast-grep-gate-"))
        self.bin = self.root / "bin"
        self.bin.mkdir()
        self.record = self.root / "record.jsonl"
        self.record.touch()

    def install(self, name, version="ast-grep 0.45.3\n", version_exit=0, test_exit=0, scan_exit=0, warning=""):
        target = self.bin / name
        target.write_text(
            FAKE_BINARY.format(
                python=sys.executable,
                version=version,
                version_exit=version_exit,
                test_exit=test_exit,
                scan_exit=scan_exit,
                warning=warning,
            ),
            encoding="utf-8",
        )
        target.chmod(0o755)
        return target

    def run(self):
        env = dict(os.environ)
        env["PATH"] = str(self.bin)
        env["FAKE_AST_GREP_RECORD"] = str(self.record)
        env.pop("PYTHONPATH", None)
        return subprocess.run(
            [sys.executable, str(CHECKER)],
            capture_output=True,
            text=True,
            env=env,
            cwd=self.root,
            check=False,
        )

    def calls(self):
        text = self.record.read_text(encoding="utf-8").strip()
        return [json.loads(line) for line in text.splitlines()] if text else []

    def cleanup(self):
        import shutil

        shutil.rmtree(self.root, ignore_errors=True)


class AstGrepGateTest(unittest.TestCase):
    def setUp(self):
        self.path = FakePath()
        self.addCleanup(self.path.cleanup)

    def assert_fails_closed(self, result):
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn(f"ast-grep=={PINNED} is required", result.stderr)
        self.assertIn(f"@ast-grep/cli@{PINNED}", result.stderr)

    def test_missing_binary_fails_closed(self):
        self.assert_fails_closed(self.path.run())
        self.assertEqual(self.path.calls(), [])

    def test_unpinned_version_fails_closed(self):
        self.path.install("ast-grep", version="ast-grep 0.51.0\n")
        self.assert_fails_closed(self.path.run())
        self.assertEqual([call[1:] for call in self.path.calls()], [["--version"]])

    def test_longer_patch_version_is_not_pinned(self):
        self.path.install("ast-grep", version="ast-grep 0.45.30\n")
        self.assert_fails_closed(self.path.run())

    def test_unrelated_tool_answer_fails_closed(self):
        self.path.install("ast-grep", version="some-other-tool 0.45.3\n")
        self.assert_fails_closed(self.path.run())

    def test_failed_version_probe_fails_closed(self):
        self.path.install("ast-grep", version="ast-grep 0.45.3\n", version_exit=2)
        self.assert_fails_closed(self.path.run())

    def test_pinned_version_runs_rule_tests_then_scan(self):
        self.path.install("ast-grep")
        result = self.path.run()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            [call[1:] for call in self.path.calls()],
            [
                ["--version"],
                ["test", "--config", "sgconfig.yml", "--skip-snapshot-tests"],
                ["scan", "--config", "sgconfig.yml", "--error"],
            ],
        )

    def test_sg_alias_is_accepted(self):
        self.path.install("sg", version="sg 0.45.3\n")
        result = self.path.run()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([call[0] for call in self.path.calls()], ["sg", "sg", "sg"])

    def test_install_warning_on_stderr_still_accepts_the_pinned_binary(self):
        # npm blocks lifecycle scripts by default and warns on every invocation;
        # the pinned CLI is still valid, so the probe must read version lines.
        self.path.install(
            "ast-grep",
            warning="[warn] postinstall script did not run; falling back to runtime binary resolution.\n",
        )
        result = self.path.run()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(
            [call[1] for call in self.path.calls()],
            ["--version", "test", "scan"],
        )

    def test_version_mentioned_inside_a_hint_is_not_a_pin(self):
        self.path.install(
            "ast-grep",
            version="",
            warning="hint: install @ast-grep/cli@0.45.3 to enable AST rules\n",
        )
        self.assert_fails_closed(self.path.run())

    def test_trailing_text_after_the_version_is_not_a_pin(self):
        self.path.install("ast-grep", version="ast-grep 0.45.3 (release build)\n")
        self.assert_fails_closed(self.path.run())

    def test_ast_grep_is_preferred_over_a_stale_sg(self):
        self.path.install("ast-grep")
        self.path.install("sg", version="sg 0.44.0\n")
        result = self.path.run()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual({call[0] for call in self.path.calls()}, {"ast-grep"})

    def test_failing_rule_tests_stop_before_the_scan(self):
        self.path.install("ast-grep", test_exit=4)
        result = self.path.run()
        self.assertEqual(result.returncode, 4, result.stderr)
        self.assertEqual([call[1] for call in self.path.calls()], ["--version", "test"])

    def test_scan_failure_propagates(self):
        self.path.install("ast-grep", scan_exit=3)
        result = self.path.run()
        self.assertEqual(result.returncode, 3, result.stderr)
        self.assertEqual([call[1] for call in self.path.calls()], ["--version", "test", "scan"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
