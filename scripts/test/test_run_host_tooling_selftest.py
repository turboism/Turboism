#!/usr/bin/env python3
"""Fail-closed contract for the host tooling selftest runner.

A selection that matches no file, and a module that defines no test, must both
report a non-zero status: CPython 3.10 (the CI interpreter) exits 0 on
"NO TESTS RAN", so a gate that only forwards the return code passes silently.
"""
from __future__ import annotations

import importlib.util
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock

ROOT = Path(__file__).resolve().parents[2]
RUNNER = ROOT / "scripts" / "test" / "run_host_tooling_selftest.py"


def load_runner():
    spec = importlib.util.spec_from_file_location("run_host_tooling_selftest", RUNNER)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class HostToolingRunnerFailClosedTest(unittest.TestCase):
    def setUp(self) -> None:
        self.runner = load_runner()

    def test_zero_test_summary_fails_even_when_unittest_exits_zero(self):
        result = subprocess.CompletedProcess(args=[], returncode=0, stdout="", stderr="Ran 0 tests in 0.001s\n")
        self.assertEqual(1, self.runner._report(result, "test_empty_contract.py"))

    def test_passing_summary_is_not_reported_as_a_failure(self):
        result = subprocess.CompletedProcess(args=[], returncode=0, stdout="", stderr="Ran 1 test in 0.001s\n")
        self.assertEqual(0, self.runner._report(result, "test_empty_contract.py"))

    def test_pattern_matching_no_file_fails(self):
        with mock.patch.multiple(
            self.runner, PATTERNS=("test_absent_host_tooling_*.py",), SHELL_TESTS=()
        ):
            self.assertEqual(1, self.runner.main())

    def test_module_without_a_test_case_fails(self):
        with tempfile.TemporaryDirectory() as root:
            test_dir = Path(root) / "scripts" / "test"
            test_dir.mkdir(parents=True)
            (test_dir / "test_empty_contract.py").write_text(
                '"""A discovered module that defines no test case."""\n', encoding="utf-8"
            )
            with mock.patch.multiple(
                self.runner,
                ROOT=Path(root),
                PATTERNS=("test_empty_contract.py",),
                SHELL_TESTS=(),
            ):
                self.assertEqual(1, self.runner.main())

    def test_module_with_a_test_case_passes(self):
        with tempfile.TemporaryDirectory() as root:
            test_dir = Path(root) / "scripts" / "test"
            test_dir.mkdir(parents=True)
            (test_dir / "test_selftest_ok.py").write_text(
                "import unittest\n\n\n"
                "class SelftestOk(unittest.TestCase):\n"
                "    def test_ok(self):\n"
                "        self.assertTrue(True)\n",
                encoding="utf-8",
            )
            with mock.patch.multiple(
                self.runner,
                ROOT=Path(root),
                PATTERNS=("test_selftest_ok.py",),
                SHELL_TESTS=(),
            ):
                self.assertEqual(0, self.runner.main())


if __name__ == "__main__":
    unittest.main()
