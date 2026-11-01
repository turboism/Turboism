#!/usr/bin/env python3
"""Offline contract for the status tokens the four shared-recorder probes may record.

The recorder rejects an unknown token only when a probe runs on a host, and the rejection
aborts the whole run (atlas-polygon once recorded NOTE). The check in
check_validation_tools_sync reads the literals instead, so these tests need no host.
"""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_validation_tools_sync as sync

ROOT = Path(__file__).resolve().parents[2]
ACCEPTED = {"PASS", "FAIL", "BLOCKED", "SKIPPED", "INFO"}


def check(source: str) -> list[str]:
    return sync.status_findings("Probe.java", source, ACCEPTED)


class AcceptedStatusesTest(unittest.TestCase):
    def test_tokens_come_from_the_shared_terminal_source(self):
        self.assertEqual(ACCEPTED, sync.accepted_statuses(ROOT))


class StatusScanTest(unittest.TestCase):
    def test_unknown_literal_is_rejected(self):
        findings = check('recordAssertion("a", "x", "y", present ? "PASS" : "NOTE");')
        self.assertEqual(1, len(findings))
        self.assertIn("'NOTE'", findings[0])
        self.assertIn("Probe.java:1", findings[0])

    def test_wrong_case_and_blank_literals_are_rejected(self):
        for status in ('"pass"', '"Pass"', '""', 'ok ? "PASS" : "fail"'):
            with self.subTest(status=status):
                self.assertTrue(check(f'recordAssertion("a", "x", "y", {status});'))

    def test_every_accepted_token_and_nested_ternary_pass(self):
        for status in ('"PASS"', '"INFO"', 'a ? "PASS" : "SKIPPED"',
                       'ready ? "PASS" : binding == null ? "BLOCKED" : "FAIL"'):
            with self.subTest(status=status):
                self.assertEqual([], check(f'recordAssertion("a", "x", "y", {status});'))

    def test_literals_inside_conditions_are_not_statuses(self):
        source = 'recordAssertion("a", "x", "y", !"unreadable".equals(state) ? "PASS" : "FAIL");'
        self.assertEqual([], check(source))

    def test_commas_and_parentheses_in_earlier_arguments_do_not_shift_the_status(self):
        source = 'recordAssertion("a, (b)", describe(1, 2), "pre=" + x + ", post=)", ok ? "PASS" : "NOTE");'
        self.assertEqual(1, len(check(source)))

    def test_identifier_status_is_resolved_through_its_assignments(self):
        good = 'String s;\ns = "PASS";\ns = "BLOCKED";\nrecordAssertion("a", "x", "y", s);'
        bad = 'String s;\ns = "PASS";\ns = "NOTE";\nrecordAssertion("a", "x", "y", s);'
        self.assertEqual([], check(good))
        self.assertEqual(1, len(check(bad)))

    def test_status_the_check_cannot_verify_is_rejected(self):
        self.assertTrue(check('recordAssertion("a", "x", "y", computeStatus());'))
        self.assertTrue(check('recordAssertion("a", "x", "y", unassigned);'))

    def test_comments_between_arguments_do_not_shift_the_status(self):
        source = ('recordAssertion("a", "x", "y",\n  // note, with, commas "NOTE"\n'
                  '  /* and "NOTE", */ ok ? "PASS" : "FAIL");')
        self.assertEqual([], check(source))
        self.assertEqual(1, len(check(source.replace('"FAIL"', '"NOTE"'))))

    def test_recorder_wrapper_declaration_is_not_a_call(self):
        source = 'private void recordAssertion(\n final String name, final Object expected, final Object actual, final String status\n) {}'
        self.assertEqual([], check(source))


class ProbeSourcesTest(unittest.TestCase):
    def test_the_four_probes_record_only_accepted_statuses(self):
        self.assertEqual(4, len(sync.PROBE_TERMINAL_SOURCES))
        for relative in sync.PROBE_TERMINAL_SOURCES:
            with self.subTest(probe=relative):
                text = (ROOT / relative).read_text(encoding="utf-8")
                self.assertTrue(list(sync.record_status_arguments(text)), "probe records no assertion")
                self.assertEqual([], sync.status_findings(relative, text, ACCEPTED))

    def test_atlas_polygon_readback_uses_info_not_note(self):
        relative = next(p for p in sync.PROBE_TERMINAL_SOURCES if "atlas-polygon" in p)
        text = (ROOT / relative).read_text(encoding="utf-8")
        self.assertIn('present ? "PASS" : "INFO"', text)
        regressed = text.replace('present ? "PASS" : "INFO"', 'present ? "PASS" : "NOTE"')
        self.assertTrue(sync.status_findings(relative, regressed, ACCEPTED))

    def test_sync_check_reports_zero_findings(self):
        self.assertEqual([], sync.findings(ROOT))


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


def statuses_by_assertion(text: str) -> dict[str, set[str]]:
    result: dict[str, set[str]] = {}
    for _, args in sync.record_calls(text):
        name = args[0].strip('"')
        if len(args) == 4 and args[0].startswith('"'):
            result.setdefault(name, set()).update(sync.STATUS_BRANCH_RE.findall(args[3]))
    return result


class ParameterBatchTransferSkipTest(unittest.TestCase):
    def setUp(self):
        self.statuses = statuses_by_assertion(PBT_SOURCE.read_text(encoding="utf-8"))

    def test_optional_paths_cannot_record_skipped(self):
        for name in PBT_OPTIONAL:
            with self.subTest(assertion=name):
                self.assertIn(name, self.statuses)
                self.assertNotIn("SKIPPED", self.statuses[name])

    def test_optional_fast_path_records_info_so_the_run_is_not_skipped(self):
        for name in ("setup.fallback.source.parameter", "setup.fallback.source"):
            self.assertIn("INFO", self.statuses[name])

    def test_only_owed_rows_can_record_skipped(self):
        skipping = {name for name, found in self.statuses.items() if "SKIPPED" in found}
        self.assertEqual(set(PBT_OWED_SKIPPED), skipping)


if __name__ == "__main__":
    unittest.main()
