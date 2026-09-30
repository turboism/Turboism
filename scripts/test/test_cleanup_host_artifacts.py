#!/usr/bin/env python3
"""Tests for Proton host validation artifact cleanup and safety boundaries."""

import os
from pathlib import Path
import tempfile
import unittest

# Add scripts/dev to sys.path
SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parent.parent
sys_path_dev = REPO_ROOT / "scripts" / "dev"
import sys
sys.path.insert(0, str(sys_path_dev))

import cleanup_host_artifacts as cleanup
from cleanup_host_artifacts import SafetyContext, SecurityError


class TestCleanupSafetyBoundaries(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory(prefix="turboism-safety-test-")
        self.test_root = Path(self.temp_dir.name)

        self.fake_repo = self.test_root / "repo"
        self.fake_repo.mkdir()
        (self.fake_repo / "build").mkdir()
        (self.fake_repo / "src").mkdir()
        (self.fake_repo / "src" / "Main.java").write_text("class Main {}")

        self.fake_golden = self.test_root / "golden_proton"
        self.fake_golden.mkdir()
        (self.fake_golden / "pfx").mkdir()
        (self.fake_golden / "pfx" / "system.reg").write_text("regdata")

        self.fake_fixture = self.test_root / "source_model.cmo3"
        self.fake_fixture.write_text("model-data")

        self.fake_host_root = self.test_root / "TurboismValidation"
        self.fake_host_root.mkdir()

        self.env = {
            "TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX": str(self.fake_golden),
            "TURBOISM_HOST_VALIDATION_HOST_ROOT": str(self.fake_host_root),
            "TURBOISM_HOST_VALIDATION_FIXTURE_5203": str(self.fake_fixture),
        }
        self.context = SafetyContext.create(self.fake_repo, self.env)

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_refuse_golden_prefix_deletion(self):
        with self.assertRaises(SecurityError):
            self.context.assert_safe_to_delete(self.fake_golden)

        with self.assertRaises(SecurityError):
            self.context.assert_safe_to_delete(self.fake_golden / "pfx")

        with self.assertRaises(SecurityError):
            self.context.assert_safe_to_delete(self.fake_golden / "pfx" / "system.reg")

    def test_refuse_source_fixture_deletion(self):
        with self.assertRaises(SecurityError):
            self.context.assert_safe_to_delete(self.fake_fixture)

        # Inode match via hard link
        hardlink = self.fake_host_root / "copied_by_hardlink.cmo3"
        os.link(self.fake_fixture, hardlink)
        with self.assertRaises(SecurityError):
            self.context.assert_safe_to_delete(hardlink)

    def test_refuse_repo_source_deletion(self):
        with self.assertRaises(SecurityError):
            self.context.assert_safe_to_delete(self.fake_repo / "src")

        with self.assertRaises(SecurityError):
            self.context.assert_safe_to_delete(self.fake_repo / "src" / "Main.java")

    def test_refuse_host_root_itself(self):
        with self.assertRaises(SecurityError):
            self.context.assert_safe_to_delete(self.fake_host_root)

    def test_symlink_does_not_traverse(self):
        # Create a task dir inside host root
        task_dir = self.fake_host_root / "test_task"
        task_dir.mkdir()
        victim_dir = self.test_root / "should_survive"
        victim_dir.mkdir()
        (victim_dir / "precious.txt").write_text("dont delete me")

        # Create symlink inside task_dir pointing to victim_dir
        link = task_dir / "evil_symlink"
        link.symlink_to(victim_dir)

        # Deleting the symlink should remove the link, NOT the contents of victim_dir
        cleanup.safe_delete_path(link, self.context)
        self.assertFalse(link.exists())
        self.assertTrue(victim_dir.is_dir())
        self.assertTrue((victim_dir / "precious.txt").is_file())


class TestCleanupScannerAndExecution(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory(prefix="turboism-cleanup-test-")
        self.test_root = Path(self.temp_dir.name)

        self.repo = self.test_root / "repo"
        self.repo.mkdir()
        self.build_dir = self.repo / "build"
        self.build_dir.mkdir()

        self.golden = self.test_root / "golden_proton"
        self.golden.mkdir()

        self.fixture = self.test_root / "original.cmo3"
        self.fixture.write_text("fixture-bytes")

        self.host_root = self.test_root / "TurboismValidation"
        self.host_root.mkdir()

        self.env_file = self.repo / ".env"
        self.env_file.write_text(f"""
TURBOISM_HOST_VALIDATION_GOLDEN_PREFIX={self.golden}
TURBOISM_HOST_VALIDATION_HOST_ROOT={self.host_root}
TURBOISM_HOST_VALIDATION_FIXTURE_5203={self.fixture}
""")

    def tearDown(self):
        self.temp_dir.cleanup()

    def test_worktree_cleanup(self):
        wt_id = "feature-test-123"
        # 1. Setup build staging
        wt_manual_test = self.build_dir / "manual-test" / wt_id
        wt_manual_test.mkdir(parents=True)
        (wt_manual_test / "bundle.jar").write_text("jar-data")

        # 2. Setup host task referencing this worktree
        task_dir = self.host_root / "sample-task" / "5302-r1" / "queue-run-1"
        task_dir.mkdir(parents=True)
        (task_dir / "launch.sh").write_text(f"TURBOISM_WORKTREE={wt_id}\nexec something")
        prefix_dir = task_dir / "prefix"
        prefix_dir.mkdir()
        (prefix_dir / "wine.log").write_text("wine logs")
        (task_dir / "copied_fixture.cmo3").write_text("copied-model")
        logs_dir = task_dir / "turboism-home" / "logs"
        logs_dir.mkdir(parents=True)
        (logs_dir / "turboism.log").write_text("runtime logs")

        # 3. Dry-run first
        res_dry = cleanup.run_cleanup(
            worktree_id=wt_id,
            clean_build_psd=False,
            dry_run=True,
            repo_root=self.repo,
            env_file=self.env_file,
        )
        self.assertTrue(res_dry["dryRun"])
        self.assertGreater(res_dry["totalItems"], 0)
        self.assertEqual(res_dry["deletedItems"], 0)
        self.assertTrue(prefix_dir.exists())
        self.assertTrue(wt_manual_test.exists())

        # 4. Real execution
        res_real = cleanup.run_cleanup(
            worktree_id=wt_id,
            clean_build_psd=False,
            dry_run=False,
            repo_root=self.repo,
            env_file=self.env_file,
        )
        self.assertFalse(res_real["dryRun"])
        self.assertGreater(res_real["deletedItems"], 0)
        self.assertFalse(prefix_dir.exists())
        self.assertFalse(wt_manual_test.exists())
        self.assertFalse((task_dir / "copied_fixture.cmo3").exists())
        self.assertFalse(logs_dir.exists())

    def test_terminal_and_build_disposables_cleanup(self):
        # 1. Create a terminal task in host_root
        task_dir = self.host_root / "atlas" / "5303-r1" / "queue-term-1"
        task_dir.mkdir(parents=True)
        evidence_dir = task_dir / "evidence"
        evidence_dir.mkdir()
        (evidence_dir / "lifecycle-result.json").write_text('{"terminalState": "succeeded"}')

        prefix_dir = task_dir / "prefix"
        prefix_dir.mkdir()
        (prefix_dir / "reg").write_text("registry")
        (task_dir / "disposable.psd").write_text("psd-content")

        # 2. Create disposable PSD directly in build/
        disposable_psd = self.build_dir / "temp-clip.psd"
        disposable_psd.write_text("temp-psd")

        res = cleanup.run_cleanup(
            all_terminal=True,
            clean_build_psd=True,
            dry_run=False,
            repo_root=self.repo,
            env_file=self.env_file,
        )
        self.assertGreater(res["deletedItems"], 0)
        self.assertFalse(prefix_dir.exists())
        self.assertFalse((task_dir / "disposable.psd").exists())
        self.assertFalse(disposable_psd.exists())

        # 3. Idempotent check
        res2 = cleanup.run_cleanup(
            all_terminal=True,
            clean_build_psd=True,
            dry_run=False,
            repo_root=self.repo,
            env_file=self.env_file,
        )
        self.assertEqual(res2["totalItems"], 0)

    def test_multi_depth_task_discovery(self):
        context = cleanup.SafetyContext.create(self.repo, cleanup.parse_local_env(self.env_file))

        # Depth 1 task: host_root / wt-task-depth1
        d1_task = self.host_root / "wt-feature-depth1"
        d1_task.mkdir()
        (d1_task / "launch.sh").write_text("echo depth1")
        (d1_task / "prefix").mkdir()
        (d1_task / "prefix" / "system.reg").write_text("reg")
        (d1_task / "copy.cmo3").write_text("model")

        # Depth 2 task: host_root / category / wt-feature-depth2
        d2_task = self.host_root / "category" / "wt-feature-depth2"
        d2_task.mkdir(parents=True)
        (d2_task / "launch.bat").write_text("echo depth2")
        (d2_task / "prefix").mkdir()
        (d2_task / "prefix" / "system.reg").write_text("reg")

        # Test scan by worktree ID for depth 1
        items_d1 = cleanup.scan_worktree_artifacts("depth1", context)
        kinds_d1 = {item.kind for item in items_d1}
        self.assertIn("prefix", kinds_d1)
        self.assertIn("fixture-copy", kinds_d1)

        # Test scan by worktree ID for depth 2
        items_d2 = cleanup.scan_worktree_artifacts("depth2", context)
        kinds_d2 = {item.kind for item in items_d2}
        self.assertIn("prefix", kinds_d2)

        # Test scan_terminal_host_artifacts finds both depth 1 and depth 2
        terminal_items = cleanup.scan_terminal_host_artifacts(context)
        terminal_paths = {item.path for item in terminal_items}
        self.assertIn(d1_task / "prefix", terminal_paths)
        self.assertIn(d1_task / "copy.cmo3", terminal_paths)
        self.assertIn(d2_task / "prefix", terminal_paths)


if __name__ == "__main__":
    unittest.main()
