#!/usr/bin/env python3
"""Offline contract for the status semantics the shared-recorder probes still owe.

The status tokens themselves are typed now: `ProbeAssertions.record` takes a
`ProbeTerminal`, so an unknown token is a compile error and the old Java literal scan is
gone. What source review still owns is business semantics: one SKIPPED assertion turns a
whole host run into SKIPPED, so an optional or fixture-dependent step recorded as SKIPPED
would silently weaken the run. The parameter-batch-transfer probe is the only probe that
records SKIPPED, and only for contract rows the standard run owes.

Runs without a host, SDK jar or network.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
PBT_SOURCE = ROOT / (
    "validation/parameter-batch-transfer-host-probe/src/dev/turboism/validation/"
    "parameterbatchtransfer/ParameterBatchTransferHostValidationPlugin.java")

# Assertions that may record SKIPPED because they are contract rows the standard run owes:
# the non-morph transferClamped source and the clamped/inverted/duplicate rows. Everything
# else that a fixture may lack is optional and records INFO. Adding a name here needs a
# written reason, because one SKIPPED turns a whole host run into SKIPPED.
PBT_OWED_SKIPPED = {
    "apply.source": "the transferClamped matrix needs a non-morph bound source",
    "row.clamped.plain": "clamp mapping row; distinct contract from the unclamped rows",
    "row.invert": "inverted mapping row when no range-contained target ran it",
    "row.clamped.duplicate": "duplicate mapping must fail before mutation",
}
# Optional or fixture-dependent steps. They must never be able to record SKIPPED.
PBT_OPTIONAL = (
    "setup.fallback.source.parameter",  # existing-form fast path, scaffold setup follows
    "setup.fallback.source",           # existing-form fast path, scaffold setup follows
    "apply.destination",               # locator step; the clamped rows carry the verdict
    "classify.morph.filter",           # nothing to filter without a morph parameter
    "classify.combined.filter",        # nothing to filter without a combined parameter
    "classify.normal.filter",          # nothing to filter without a normal parameter
)
# The two fast-path rows that did not run must record evidence, never PASS.
PBT_FAST_PATH_INFO = ("setup.fallback.source.parameter", "setup.fallback.source")

RECORD_CALL = "recordAssertion("
SKIPPED = "ProbeTerminal.SKIPPED"
STATUS_TOKEN_RE = re.compile(r"ProbeTerminal\.([A-Z]+)")
QUOTED_STATUS_RE = re.compile(r'"(?:PASS|FAIL|BLOCKED|SKIPPED|INFO)"')


def call_arguments(source: str, start: int) -> list[str]:
    """Top-level arguments of the call whose open paren ends at start."""
    depth, index = 1, start
    arguments, current = [], []
    in_string, escaped = False, False
    while index < len(source):
        char = source[index]
        if in_string:
            current.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                in_string = False
        elif char == '"':
            in_string = True
            current.append(char)
        elif char == "(":
            depth += 1
            current.append(char)
        elif char == ")":
            depth -= 1
            if depth == 0:
                arguments.append("".join(current))
                return arguments
            current.append(char)
        elif char == "," and depth == 1:
            arguments.append("".join(current))
            current = []
        else:
            current.append(char)
        index += 1
    raise AssertionError("unbalanced recordAssertion call")


def record_calls(source: str):
    """(assertion name, status argument source) for each literal-named recorder call."""
    for match in re.finditer(re.escape(RECORD_CALL), source):
        arguments = call_arguments(source, match.end())
        if len(arguments) != 4 or "String status" in arguments[3]:
            continue
        name = arguments[0].strip()
        if not name.startswith('"') or not name.endswith('"'):
            continue
        yield name.strip('"'), arguments[3]


def statuses_by_assertion(source: str) -> dict[str, set[str]]:
    result: dict[str, set[str]] = {}
    for name, status in record_calls(source):
        result.setdefault(name, set()).update(STATUS_TOKEN_RE.findall(status))
    return result


class ParameterBatchTransferSkipTest(unittest.TestCase):
    def setUp(self):
        self.source = PBT_SOURCE.read_text(encoding="utf-8")
        self.statuses = statuses_by_assertion(self.source)

    def test_only_owed_rows_can_record_skipped(self):
        skipping = {name for name, found in self.statuses.items() if "SKIPPED" in found}
        self.assertEqual(set(PBT_OWED_SKIPPED), skipping)

    def test_optional_paths_cannot_record_skipped(self):
        for name in PBT_OPTIONAL:
            with self.subTest(assertion=name):
                self.assertIn(name, self.statuses)
                self.assertNotIn("SKIPPED", self.statuses[name])

    def test_optional_fast_path_records_info_so_the_run_is_not_skipped(self):
        for name in PBT_FAST_PATH_INFO:
            self.assertIn("INFO", self.statuses[name])

    def test_status_arguments_are_typed_in_every_recorder_call(self):
        # A quoted status argument is what the typed recorder replaced.
        for name, status in record_calls(self.source):
            with self.subTest(assertion=name):
                self.assertEqual([], QUOTED_STATUS_RE.findall(status))
                self.assertTrue(STATUS_TOKEN_RE.search(status), status)


if __name__ == "__main__":
    unittest.main()
