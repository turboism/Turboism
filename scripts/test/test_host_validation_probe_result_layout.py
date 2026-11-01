#!/usr/bin/env python3
"""Pin the result-file layout the four shared-recorder probes publish.

The Runner's first screen reads a count as soon as it sees the pass line, so the counter
lines must precede the terminal line. The post-containment evidence gate reads the same
counters and the per-assertion records. Two layers check this without a host:

- the shared recorder's real output (compiled with javac) is fed to the evidence gate and to
  the Runner's own count reader;
- each probe's writer is checked in source order, because the probes' own writers cannot run
  without the SDK and a Cubism host.
"""
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
PREVIEW = ROOT / "scripts/preview"
sys.path.insert(0, str(PREVIEW))
sys.path.insert(0, str(Path(__file__).resolve().parent))
import host_validation_evidence as evidence
import test_host_validation_probe_wrappers as wrappers
import check_validation_tools_sync as sync
import test_cubism_host_validation_result as runner_tests

RUNNER = (PREVIEW / "run-cubism-host-validation.sh").read_text(encoding="utf-8")
COUNT_BODY = RUNNER.split("result_file_assertion_count() {", 1)[1].split("<<'PY'\n", 1)[1].split("\nPY\n", 1)[0]
SHARED = ROOT / "validation/shared"
VERDICT = SHARED / "src/dev/turboism/validation/shared/verdict"
DUMP = SHARED / "test/dev/turboism/validation/shared/verdict/ProbeResultLayoutDump.java"

# probe source -> (layout of the assertion records, terminal key the probe writes last)
PROBES = {
    "animation-timeline": ("animationtimeline/AnimationTimelineHostValidationPlugin.java", "appendIndexed", "terminal"),
    "atlas-polygon": ("atlaspolygon/AtlasPolygonHostValidationPlugin.java", "appendNamed", "status"),
    "clipmask-viewer": ("clipmaskviewer/ClipMaskViewerHostValidationPlugin.java", "appendNamed", "status"),
    "parameter-batch-transfer": ("parameterbatchtransfer/ParameterBatchTransferHostValidationPlugin.java", "appendNamed", "status"),
}


def probe_source(task: str) -> str:
    relative = PROBES[task][0]
    path = ROOT / f"validation/{task}-host-probe/src/dev/turboism/validation" / relative
    return path.read_text(encoding="utf-8")


def writer_region(text: str) -> str:
    """The complete writer before publication, including anything before the counters."""
    start = re.search(r"private void writeResult(?:File)?\(", text).start()
    return text[start:text.index("Files.writeString", start)]


def layout_findings(task: str, text: str) -> list[str]:
    """Check actual writer statements, including early/duplicate terminal lines."""
    _, records, terminal_key = PROBES[task]
    region = sync.strip_comments(writer_region(text))
    counters = list(re.finditer(r"assertions\.appendCounters\(report\);", region))
    rows = list(re.finditer(r"assertions\." + records + r"\(report(?:, true)?\);", region))
    terminals = list(re.finditer(r'report\.append\("' + terminal_key + r'=.*?;', region, re.S))
    if len(counters) != 1 or len(rows) != 1 or len(terminals) != 1:
        return [f"{task}: expected exactly one counters, records and terminal statement"]
    findings = []
    counter, row, terminal = counters[0], rows[0], terminals[0]
    if not counter.start() < row.start() < terminal.start():
        findings.append(f"{task}: expected counters, records, then terminal")
    statement = re.sub(r"\s+", "", terminal.group())
    expression = 'terminal' if task == 'atlas-polygon' else 'assertions.terminal().name()'
    expected = f'report.append("{terminal_key}=").append({expression}).append(\'\\n\');'
    if statement != expected:
        findings.append(f"{task}: terminal must come from the shared recorder")
    if expression == 'terminal' and 'final String terminal = assertions.terminal().name();' not in region:
        findings.append(f"{task}: terminal variable must come from the shared recorder")
    if re.search(r"\breport\.", region[terminal.end():]):
        findings.append(f"{task}: report is changed after the terminal line")
    return findings


class ProbeWriterOrderTest(unittest.TestCase):
    def test_every_probe_writes_counters_then_assertions_then_the_terminal_line(self):
        for task in PROBES:
            with self.subTest(task=task):
                self.assertEqual([], layout_findings(task, probe_source(task)))

    def test_probe_terminal_key_matches_the_wrapper_pass_line(self):
        for task, (result_file, key) in wrappers.CONTRACT.items():
            with self.subTest(task=task):
                self.assertEqual(key, PROBES[task][2])
                self.assertIn(Path(result_file).name, probe_source(task))

    def test_writer_regressions_are_reported(self):
        for task in PROBES:
            text = probe_source(task)
            _, records, key = PROBES[task]
            terminal = re.search(r'report\.append\("' + key + r'=.*?;', text, re.S).group()
            without_terminal = text.replace(terminal, '')
            mutations = {
                "no counters": text.replace("assertions.appendCounters(report);", ""),
                "no records": text.replace(f"assertions.{records}(report", "unused(report"),
                "terminal before counters": without_terminal.replace(
                    'assertions.appendCounters(report);', terminal + '\nassertions.appendCounters(report);'),
                "terminal before records": without_terminal.replace(
                    f'assertions.{records}(', terminal + f'\nassertions.{records}('),
                "duplicate terminal": text.replace(terminal, terminal + terminal),
                "hardcoded pass": text.replace(terminal, f'report.append("{key}=PASS\\n");'),
                "record after terminal": text.replace(terminal, terminal + 'report.append("extra=1\\n");'),
            }
            for mutation, changed in mutations.items():
                with self.subTest(task=task, mutation=mutation):
                    self.assertTrue(layout_findings(task, changed))


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


if __name__ == "__main__":
    unittest.main()
