#!/usr/bin/env python3
"""Pin the result publication and the probe packaging the four shared-recorder probes rely on.

The Runner's first screen reads a count as soon as it sees the pass line, and the
post-containment evidence gate reads the same counters and per-assertion records. Both are
proven here against the shared recorder's real output (compiled with javac), not against the
probes' source shape: counters, records and the terminal line are published by one shared
call, so a probe cannot reorder or truncate them.

The build test drives the shared probe build helper with a synthetic probe, so worktree SDK
resolution, javac, META-INF staging, packaging and the packaged descriptor requirement are
proven without a Cubism host or an SDK jar from a real build.
"""
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PREVIEW = ROOT / "scripts/preview"
sys.path.insert(0, str(PREVIEW))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import host_validation_evidence as evidence
import test_host_validation_probe_wrappers as wrappers
import test_cubism_host_validation_result as runner_tests

RUNNER = (PREVIEW / "run-cubism-host-validation.sh").read_text(encoding="utf-8")
COUNT_BODY = RUNNER.split("result_file_assertion_count() {", 1)[1].split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
SHARED = ROOT / "validation/shared"
VERDICT = SHARED / "src/dev/turboism/validation/shared/verdict"
DUMP = SHARED / "test/dev/turboism/validation/shared/verdict/ProbeResultLayoutDump.java"

# probe source -> terminal key the probe publishes last.
PROBES = {
    "animation-timeline": ("animationtimeline/AnimationTimelineHostValidationPlugin.java", "terminal"),
    "atlas-polygon": ("atlaspolygon/AtlasPolygonHostValidationPlugin.java", "status"),
    "clipmask-viewer": ("clipmaskviewer/ClipMaskViewerHostValidationPlugin.java", "status"),
    "parameter-batch-transfer": ("parameterbatchtransfer/ParameterBatchTransferHostValidationPlugin.java", "status"),
}

BUILD_TOOLS = ("bash", "javac", "java", "jar")


def probe_source(task: str) -> str:
    relative = PROBES[task][0]
    path = ROOT / f"validation/{task}-host-probe/src/dev/turboism/validation" / relative
    return path.read_text(encoding="utf-8")


class ProducerConsumerTest(unittest.TestCase):
    def test_every_probe_writes_the_result_file_its_wrapper_reads(self):
        # The wrapper's --result-file and terminal key are the consumer side of one contract;
        # a probe that renames its file or publishes another key fails the run as MISSING.
        for task, (result_file, key) in wrappers.CONTRACT.items():
            with self.subTest(task=task):
                self.assertEqual(key, PROBES[task][1])
                self.assertIn(Path(result_file).name, probe_source(task))


class RecorderOutputTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory()
        subprocess.run(
            ["javac", "--release", "17", "-d", cls.temp.name,
             str(VERDICT / "ProbeTerminal.java"), str(VERDICT / "ProbeAssertions.java"), str(DUMP)],
            check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.temp.cleanup()

    def dump(self, layout: str, key: str, *statuses: str) -> str:
        return subprocess.run(
            ["java", "-cp", self.temp.name, "dev.turboism.validation.shared.verdict.ProbeResultLayoutDump",
             layout, key, *statuses],
            check=True, capture_output=True, text=True).stdout

    def gate(self, text: str, key: str, require: bool = True) -> dict:
        with tempfile.TemporaryDirectory() as directory:
            task = Path(directory)
            result = task / "turboism-home/state/result.properties"
            result.parent.mkdir(parents=True)
            result.write_text(text, encoding="utf-8")
            options = {"--result-file": ["state/result.properties"],
                       "--result-pass-line": [f"{key}=PASS"],
                       "--result-fail-line": [f"{key}={status}" for status in ("FAIL", "BLOCKED", "SKIPPED")]}
            if require:
                options["--require-assertions"] = ["true"]
            return evidence.terminal_result({"task": task, "options": options})

    def runner_count(self, text: str):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "result.properties"
            path.write_text(text, encoding="utf-8")
            run = subprocess.run([sys.executable, "-c", COUNT_BODY, str(path)], text=True, capture_output=True)
        return int(run.stdout.strip()) if run.returncode == 0 else None

    def test_counters_precede_assertions_and_the_terminal_line_is_last(self):
        for layout, key in (("named", "status"), ("indexed", "terminal")):
            with self.subTest(layout=layout):
                lines = self.dump(layout, key, "PASS", "PASS", "INFO").splitlines()
                counters = [i for i, line in enumerate(lines) if line.startswith("assertions")]
                records = [i for i, line in enumerate(lines) if line.startswith("assertion.")]
                self.assertTrue(counters and records)
                self.assertLess(max(counters), min(records))
                self.assertTrue(lines[-1].startswith(f"{key}="))
                self.assertEqual(1, sum(line.startswith(f"{key}=") for line in lines))

    def test_pass_run_is_accepted_by_the_runner_screen_and_the_evidence_gate(self):
        for layout, key in (("named", "status"), ("indexed", "terminal")):
            with self.subTest(layout=layout):
                text = self.dump(layout, key, "PASS", "PASS", "INFO")
                self.assertEqual(f"{key}=PASS", text.splitlines()[-1])
                self.assertEqual(2, self.runner_count(text))
                self.assertEqual(0, runner_tests.TerminalPublicationRace().poll(
                    text, preexisting=True, pass_line=f"{key}=PASS",
                    fail_lines=[f"{key}={status}" for status in wrappers.BLOCKING],
                    require_assertions=True).returncode)
                terminal = self.gate(text, key)
                self.assertTrue(terminal["passed"], terminal)
                self.assertEqual("assertions.verdict", terminal["assertionCountKey"])
                self.assertEqual(2, terminal["assertionCount"])
                self.assertEqual([], terminal["blockingStatusRecords"])

    def test_counters_alone_in_the_first_lines_satisfy_the_runner_before_the_terminal_line(self):
        # The Runner acts on the pass line the moment it appears; everything it needs must
        # already be in the file at that point.
        text = self.dump("named", "status", "PASS")
        before_terminal = text[:text.rindex("status=PASS")]
        self.assertEqual(1, self.runner_count(before_terminal))

    def test_non_pass_runs_fail_the_gate_for_every_status(self):
        scenarios = {
            "FAIL": ("PASS", "FAIL"),
            "BLOCKED": ("PASS", "BLOCKED"),
            "SKIPPED": ("PASS", "SKIPPED"),
        }
        for layout, key in (("named", "status"), ("indexed", "terminal")):
            for terminal_name, statuses in scenarios.items():
                with self.subTest(layout=layout, terminal=terminal_name):
                    text = self.dump(layout, key, *statuses)
                    self.assertEqual(f"{key}={terminal_name}", text.splitlines()[-1])
                    self.assertFalse(self.gate(text, key)["passed"])
                    self.assertEqual(23, runner_tests.TerminalPublicationRace().poll(
                        text, preexisting=True, pass_line=f"{key}=PASS",
                        fail_lines=[f"{key}={status}" for status in wrappers.BLOCKING],
                        require_assertions=True).returncode)

    def test_empty_and_evidence_only_runs_publish_a_zero_verdict_count(self):
        for statuses in ((), ("INFO",), ("INFO", "INFO")):
            for layout, key in (("named", "status"), ("indexed", "terminal")):
                with self.subTest(layout=layout, statuses=statuses):
                    text = self.dump(layout, key, *statuses)
                    self.assertEqual(f"{key}=BLOCKED", text.splitlines()[-1])
                    self.assertEqual(0, self.runner_count(text))
                    terminal = self.gate(text, key)
                    self.assertTrue(terminal["emptyRun"])
                    self.assertFalse(terminal["passed"])


@unittest.skipUnless(all(shutil.which(tool) for tool in BUILD_TOOLS),
                     "probe build contract needs " + ", ".join(BUILD_TOOLS))
class SharedProbeBuildTest(unittest.TestCase):
    """The shared build helper, exercised end to end on a synthetic probe."""

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.repo = Path(self.temp.name)
        shared = self.repo / "validation/shared"
        shared.mkdir(parents=True)
        shutil.copy(SHARED / "probe-build.sh", shared / "probe-build.sh")
        dev = self.repo / "scripts/dev"
        dev.mkdir(parents=True)
        (dev / "worktree-id.sh").write_text("#!/bin/sh\nprintf 'wt\\n'\n", encoding="utf-8")
        (dev / "worktree-id.sh").chmod(0o755)
        sdk = self.repo / "build/worktree/wt/sdk/libs/sdk-test.jar"
        sdk.parent.mkdir(parents=True)
        with zipfile.ZipFile(sdk, "w") as jar:
            jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        source = self.repo / "validation/fake-probe/src/dev/fake/Fake.java"
        source.parent.mkdir(parents=True)
        source.write_text("package dev.fake;\npublic final class Fake {}\n", encoding="utf-8")
        self.manifest = self.repo / "validation/fake-probe/src/META-INF/turboism/plugin.json"
        self.manifest.parent.mkdir(parents=True)
        self.manifest.write_text(
            '{"format":"turboism.plugin.meta","schemaVersion":3,'
            '"id":"dev.turboism.validation.fake","version":"0.1.0",'
            '"entrypoints":["dev.fake.Fake"],"permissions":[],"capabilities":[],'
            '"environment":{"requiresCubism":true,"ui":"embedded"}}\n',
            encoding="utf-8")
        self.build_script = self.repo / "build.sh"
        self.write_build_script()

    def write_build_script(self, required_entries: tuple[str, ...] = ()) -> None:
        self.build_script.write_text(
            "set -euo pipefail\n"
            f'source "{self.repo.as_posix()}/validation/shared/probe-build.sh"\n'
            f"PROBE_REQUIRED_ENTRIES=({' '.join(required_entries)})\n"
            "probe_build_plugin_jar validation/fake-probe build/fake.jar"
            " validation/fake-probe/src/dev/fake/Fake.java\n",
            encoding="utf-8")

    def tearDown(self):
        self.temp.cleanup()

    def build(self) -> subprocess.CompletedProcess:
        return subprocess.run(["bash", str(self.build_script)], text=True, capture_output=True)

    def test_build_stages_metadata_and_requires_the_packaged_descriptor(self):
        result = self.build()
        self.assertEqual(0, result.returncode, result.stderr)
        jar = self.repo / "build/fake.jar"
        with zipfile.ZipFile(jar) as packaged:
            names = set(packaged.namelist())
        self.assertIn("META-INF/turboism/plugin.json", names)
        self.assertIn("dev/fake/Fake.class", names)
        self.assertIn(str(jar), result.stdout)

    def test_missing_packaged_descriptor_fails_the_build(self):
        self.manifest.unlink()
        result = self.build()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("probe jar is missing META-INF/turboism/plugin.json", result.stderr)

    def test_call_site_required_entries_are_enforced(self):
        self.write_build_script(required_entries=("META-INF/turboism/i18n/messages.properties",))
        result = self.build()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("probe jar is missing META-INF/turboism/i18n/messages.properties", result.stderr)

    def test_missing_sdk_jar_fails_instead_of_borrowing_another_worktree(self):
        other = self.repo / "build/worktree/other/sdk/libs/sdk-other.jar"
        other.parent.mkdir(parents=True)
        with zipfile.ZipFile(other, "w") as jar:
            jar.writestr("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n")
        shutil.rmtree(self.repo / "build/worktree/wt")
        result = self.build()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("no sdk jar found", result.stderr)


if __name__ == "__main__":
    unittest.main()
