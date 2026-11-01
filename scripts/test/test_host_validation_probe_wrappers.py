#!/usr/bin/env python3
"""Pin the argv the four shared-recorder probe wrappers hand to the generic Runner.

A wrapper that drops --require-assertions or a failure line re-opens the path where an
empty, blocked or skipped probe run passes the Runner's first screen. The wrappers run
here against a stub Runner that prints its argv, so no host, SDK jar or network is used.
"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PREVIEW = ROOT / "scripts/preview"
sys.path.insert(0, str(PREVIEW))
from hvq.snapshot import BOOLEAN_FLAGS

# task -> (result file the probe writes, terminal key).
CONTRACT = {
    "animation-timeline": ("state/animation-timeline-validation-result.properties", "terminal"),
    "atlas-polygon": ("state/atlas-polygon-validation-result.properties", "status"),
    "clipmask-viewer": ("state/clipmask-viewer-validation-result.properties", "status"),
    "parameter-batch-transfer": ("state/parameter-batch-transfer-validation-result.properties", "status"),
}
LEGACY_CONTRACT = {
    "acp": "state/acp-host-validation.properties",
    "edit-protocol": "state/edit-protocol-host-validation-result.properties",
    "fps": "state/dev.turboism.validation.fps/result.txt",
}
BLOCKING = ("FAIL", "BLOCKED", "SKIPPED")


def admitted_version(task: str) -> str:
    manifest = json.loads((PREVIEW / "host-validation-tasks.json").read_text(encoding="utf-8"))
    return manifest["tasks"][task]["versions"][-1]


class WrapperArgvTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        cls.repo = Path(cls.temp.name)
        preview = cls.repo / "scripts/preview"
        preview.mkdir(parents=True)
        for name in ("host-validation-env.sh", "host-validation-tasks.json"):
            shutil.copy(PREVIEW / name, preview / name)
        for task in (*CONTRACT, *LEGACY_CONTRACT):
            shutil.copy(PREVIEW / f"run-{task}-host-validation.sh", preview)
        dev = cls.repo / "scripts/dev"
        dev.mkdir(parents=True)
        for name in ("worktree-id.sh", "worktree-id-lib.sh"):
            shutil.copy(ROOT / "scripts/dev" / name, dev / name)
        stub = preview / "run-cubism-host-validation.sh"
        stub.write_text('#!/usr/bin/env bash\nprintf "%s\\n" "$@"\n', encoding="utf-8")
        # Only the stub Runner runs; these dependencies satisfy wrapper preflight checks.
        shutil.copy(PREVIEW / "fps-resize-driver.sh", preview)
        seed = cls.repo / f"testing/host-validation/acp/settings-{admitted_version('acp')}.properties"
        seed.parent.mkdir(parents=True)
        seed.write_text("# synthetic seed\n", encoding="utf-8")
        build = cls.repo / "build"
        for path in (build / "preview/wrap/turboism-agent.jar",
                     build / "worktree/wrap/atlas-dalsoo-polygon/libs/atlas-dalsoo-polygon-1.jar",
                     build / "atlas-polygon-validation-exerciser.jar",
                     build / "fps-host-validation-exerciser.jar"):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"stub")

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def argv(self, task: str) -> list[str]:
        version = admitted_version(task)
        env = {key: value for key, value in os.environ.items()
               if not key.startswith("TURBOISM_")}
        env.update({
            "TURBOISM_WORKTREE_ID": "wrap",
            f"TURBOISM_HOST_VALIDATION_FIXTURE_{version}": str(self.repo / "fixture.cmo3"),
            f"TURBOISM_HOST_VALIDATION_FIXTURE_{version}_SHA256": "0" * 64,
        })
        result = subprocess.run(
            ["bash", str(self.repo / f"scripts/preview/run-{task}-host-validation.sh"), version, "r1"],
            env=env, text=True, capture_output=True, check=False)
        self.assertEqual(0, result.returncode, result.stderr)
        return result.stdout.splitlines()

    @staticmethod
    def values(argv: list[str], flag: str) -> list[str]:
        return [argv[index + 1] for index, token in enumerate(argv[:-1]) if token == flag]

    def test_require_assertions_is_a_boolean_flag_the_gate_can_read(self):
        # layout() in the evidence gate treats flags outside BOOLEAN_FLAGS as taking a value
        # and would swallow the next argument.
        self.assertIn("--require-assertions", BOOLEAN_FLAGS)

    def test_every_wrapper_requires_assertions_and_fails_closed(self):
        for task, (result_file, key) in CONTRACT.items():
            with self.subTest(task=task):
                argv = self.argv(task)
                self.assertEqual(1, argv.count("--require-assertions"))
                self.assertEqual([result_file], self.values(argv, "--result-file"))
                self.assertEqual([f"{key}=PASS"], self.values(argv, "--result-pass-line"))
                self.assertEqual(sorted(f"{key}={status}" for status in BLOCKING),
                                 sorted(self.values(argv, "--result-fail-line")))

    def test_legacy_wrappers_keep_the_count_free_result_contract(self):
        for task, result_file in LEGACY_CONTRACT.items():
            with self.subTest(task=task):
                argv = self.argv(task)
                self.assertNotIn("--require-assertions", argv)
                self.assertEqual([result_file], self.values(argv, "--result-file"))
                self.assertEqual(["status=PASS"], self.values(argv, "--result-pass-line"))
                self.assertEqual(["status=FAIL"], self.values(argv, "--result-fail-line"))

    def test_require_assertions_never_swallows_the_next_option(self):
        # The Runner reads --require-assertions as a bare flag; a value after it would shift
        # every later option.
        for task in CONTRACT:
            with self.subTest(task=task):
                argv = self.argv(task)
                self.assertIn("--require-assertions", argv)
                following = argv[argv.index("--require-assertions") + 1]
                self.assertTrue(following.startswith("--"), following)

    def test_failure_markers_cover_blocked_and_skipped_runs(self):
        for task in CONTRACT:
            with self.subTest(task=task):
                markers = self.values(self.argv(task), "--failure-marker")
                for status in BLOCKING:
                    self.assertTrue(any(marker.endswith(f"status={status}") for marker in markers),
                                    f"{task}: no failure marker for status={status}")


if __name__ == "__main__":
    unittest.main()
