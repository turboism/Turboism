#!/usr/bin/env python3
"""Negative self-test for the host-artifact cleanup environment merge.

`cleanup_host_artifacts.py` used to read only the repository `.env`, so a red-line
variable exported in the process environment (golden prefix, host root, fixture
sources) was ignored and the corresponding guard silently disappeared. This
self-test pins the merged precedence and proves the guards stay effective:

  1. `.env`-only variable reaches the safety context
  2. process-environment-only variable reaches the safety context
  3. a key defined in both resolves to the process value
  4. the golden prefix exported only in the process environment is refused
  5. a fixture exported only in the process environment is refused

Every fixture is a temporary directory; no host artifact is scanned or deleted.
"""

from __future__ import annotations

import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS_DIR / "dev"))

import cleanup_host_artifacts as cleanup  # noqa: E402
from cleanup_host_artifacts import SafetyContext, SecurityError  # noqa: E402

GOLDEN_KEY = "TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX"
HOST_ROOT_KEY = "TURBOISM_HOST_VALIDATION_HOST_ROOT"
FIXTURE_KEY = "TURBOISM_HOST_VALIDATION_FIXTURE_SELFTEST"


class CleanupEnvMergeTest(unittest.TestCase):
    def setUp(self) -> None:
        self._temp = tempfile.TemporaryDirectory(prefix="turboism-cleanup-env-selftest-")
        self.root = Path(self._temp.name)
        self.repo = self.root / "repo"
        self.repo.mkdir()
        self.env_file = self.repo / ".env"

    def tearDown(self) -> None:
        self._temp.cleanup()

    def write_env(self, lines: str) -> None:
        self.env_file.write_text(lines, encoding="utf-8")

    def test_dotenv_only_value_is_used(self) -> None:
        golden = self.root / "dotenv-golden"
        golden.mkdir()
        self.write_env(f"{GOLDEN_KEY}={golden}\n")
        env = cleanup.resolve_env(self.env_file, {})
        self.assertEqual(Path(golden).resolve(), SafetyContext.create(self.repo, env).golden_prefix)

    def test_process_environment_only_value_is_used(self) -> None:
        golden = self.root / "process-golden"
        golden.mkdir()
        self.write_env("")
        env = cleanup.resolve_env(self.env_file, {GOLDEN_KEY: str(golden)})
        self.assertEqual(Path(golden).resolve(), SafetyContext.create(self.repo, env).golden_prefix)

    def test_process_environment_wins_for_shared_keys(self) -> None:
        from_env = self.root / "from-dotenv"
        from_process = self.root / "from-process"
        from_env.mkdir()
        from_process.mkdir()
        self.write_env(f"{HOST_ROOT_KEY}={from_env}\n")
        env = cleanup.resolve_env(self.env_file, {HOST_ROOT_KEY: str(from_process)})
        self.assertEqual(
            Path(from_process).resolve(),
            SafetyContext.create(self.repo, env).host_root,
            "the process environment must take precedence over .env",
        )

    def test_golden_prefix_from_process_environment_stays_protected(self) -> None:
        golden = self.root / "protected-golden"
        (golden / "pfx").mkdir(parents=True)
        (golden / "pfx" / "system.reg").write_text("registry", encoding="utf-8")
        self.write_env("")
        env = cleanup.resolve_env(self.env_file, {GOLDEN_KEY: str(golden)})
        context = SafetyContext.create(self.repo, env)
        for candidate in (golden, golden / "pfx", golden / "pfx" / "system.reg"):
            with self.assertRaises(SecurityError):
                context.assert_safe_to_delete(candidate)

    def test_fixture_from_process_environment_stays_protected(self) -> None:
        fixture = self.root / "source-model.cmo3"
        fixture.write_text("model-source", encoding="utf-8")
        self.write_env("")
        env = cleanup.resolve_env(self.env_file, {FIXTURE_KEY: str(fixture)})
        context = SafetyContext.create(self.repo, env)
        with self.assertRaises(SecurityError):
            context.assert_safe_to_delete(fixture)

    def test_run_cleanup_accepts_explicit_environ(self) -> None:
        golden = self.root / "run-golden"
        golden.mkdir()
        self.write_env("")
        result = cleanup.run_cleanup(
            all_terminal=True,
            clean_build_psd=False,
            dry_run=True,
            repo_root=self.repo,
            env_file=self.env_file,
            environ={GOLDEN_KEY: str(golden), HOST_ROOT_KEY: str(self.root / "host-root")},
        )
        self.assertTrue(result["dryRun"])
        for item in result["items"]:
            self.assertFalse(
                str(golden) in item["path"],
                f"golden prefix leaked into cleanup candidates: {item['path']}",
            )


if __name__ == "__main__":
    unittest.main(verbosity=2)
