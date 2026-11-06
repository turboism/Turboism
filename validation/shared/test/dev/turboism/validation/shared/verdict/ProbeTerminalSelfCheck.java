package dev.turboism.validation.shared.verdict;

import java.util.List;

/**
 * Offline contract check for {@link ProbeTerminal} and {@link ProbeAssertions}.
 *
 * <p>Run with {@code validation/shared/check-probe-terminal.sh}. Exits non-zero on the first
 * violated case so a probe verdict regression cannot pass silently. Unknown status tokens
 * are not exercised here: the recorder takes {@link ProbeTerminal}, so an unknown token is a
 * compile error in every probe rather than a run-time string.</p>
 */
public final class ProbeTerminalSelfCheck {
    private static int checked;

    private ProbeTerminalSelfCheck() {}

    public static void main(final String[] args) {
        check("all-pass", ProbeTerminal.PASS, ProbeTerminal.compute(
            List.of(ProbeTerminal.PASS, ProbeTerminal.PASS)));
        check("all-blocked", ProbeTerminal.BLOCKED, ProbeTerminal.compute(
            List.of(ProbeTerminal.BLOCKED, ProbeTerminal.BLOCKED)));
        check("all-skipped", ProbeTerminal.SKIPPED, ProbeTerminal.compute(
            List.of(ProbeTerminal.SKIPPED, ProbeTerminal.SKIPPED)));
        check("no-assertions", ProbeTerminal.BLOCKED, ProbeTerminal.compute(List.of()));
        check("mixed-fail-wins", ProbeTerminal.FAIL, ProbeTerminal.compute(
            List.of(ProbeTerminal.PASS, ProbeTerminal.SKIPPED, ProbeTerminal.BLOCKED,
                ProbeTerminal.FAIL)));
        check("mixed-blocked-before-skipped", ProbeTerminal.BLOCKED, ProbeTerminal.compute(
            List.of(ProbeTerminal.PASS, ProbeTerminal.SKIPPED, ProbeTerminal.BLOCKED)));
        check("mixed-skipped", ProbeTerminal.SKIPPED, ProbeTerminal.compute(
            List.of(ProbeTerminal.PASS, ProbeTerminal.PASS, ProbeTerminal.SKIPPED)));
        check("evidence-only-skipped", ProbeTerminal.BLOCKED, ProbeTerminal.compute(
            List.of(ProbeTerminal.INFO, ProbeTerminal.INFO)));
        check("evidence-only-with-pass", ProbeTerminal.PASS, ProbeTerminal.compute(
            List.of(ProbeTerminal.INFO, ProbeTerminal.PASS, ProbeTerminal.INFO)));

        rejected("null-status", null);

        final ProbeAssertions recorder = new ProbeAssertions();
        check("empty-recorder", ProbeTerminal.BLOCKED, recorder.terminal());
        recorder.record("fixture.modelDoc", "active document", null, ProbeTerminal.BLOCKED);
        recorder.record("read.enumerate", "fixture built", "no fixture", ProbeTerminal.BLOCKED);
        check("all-blocked-recorder", ProbeTerminal.BLOCKED, recorder.terminal());
        equal("all-blocked-counts",
            "assertions=2 verdictAssertions=2 pass=0 blocked=2 fail=0 skipped=0 info=0",
            recorder.counts());

        final ProbeAssertions skipped = new ProbeAssertions();
        skipped.record("matrix.morph", "morph source row", "none", ProbeTerminal.SKIPPED);
        check("skipped-recorder", ProbeTerminal.SKIPPED, skipped.terminal());

        final ProbeAssertions mixed = new ProbeAssertions();
        mixed.record("canvas.width", 1920, 1920, ProbeTerminal.PASS);
        mixed.record("diag.readOnly", "read-only flag", "true", ProbeTerminal.INFO);
        mixed.record("layout.autoArrange", "arranged", "skipped by model", ProbeTerminal.SKIPPED);
        check("mixed-recorder", ProbeTerminal.SKIPPED, mixed.terminal());
        equal("mixed-counts",
            "assertions=3 verdictAssertions=2 pass=1 blocked=0 fail=0 skipped=1 info=1",
            mixed.counts());

        final StringBuilder indexed = new StringBuilder("hostVersion=test\n");
        mixed.appendPublication(indexed, ProbeAssertions.Records.INDEXED, "terminal");
        contains("indexed-layout", indexed.toString(),
            "assertion.0.name=canvas.width\nassertion.0.expected=1920\n"
                + "assertion.0.actual=1920\nassertion.0.status=PASS\n");
        contains("indexed-layout-status", indexed.toString(),
            "assertion.2.status=SKIPPED\n");
        contains("indexed-publication-order", indexed.toString(),
            "assertions.info=1\nassertion.0.name=canvas.width\n");

        final StringBuilder escaped = new StringBuilder();
        final ProbeAssertions properties = new ProbeAssertions();
        properties.record("scene.matrix", "a=b", "c\\d", ProbeTerminal.PASS);
        properties.appendPublication(escaped, ProbeAssertions.Records.INDEXED, "terminal");
        contains("indexed-escaped", escaped.toString(),
            "assertion.0.expected=a\\=b\nassertion.0.actual=c\\\\d\n");

        final StringBuilder published = new StringBuilder();
        mixed.appendPublication(published, ProbeAssertions.Records.NAMED, "status");
        contains("named-counters", published.toString(),
            "assertions=3\nassertions.verdict=2\nassertions.pass=1\nassertions.blocked=0\n"
                + "assertions.fail=0\nassertions.skipped=1\nassertions.info=1\n");
        contains("named-layout", published.toString(),
            "assertion.canvas.width.expected=1920\nassertion.canvas.width.actual=1920\n"
                + "assertion.canvas.width.status=PASS\n");
        contains("named-layout-skipped", published.toString(),
            "assertion.layout.autoArrange.status=SKIPPED\n");
        equal("named-terminal-last", "status=SKIPPED", published.toString().lines()
            .reduce((first, second) -> second).orElse(""));

        System.out.println("probe-terminal-selfcheck: OK cases=" + checked);
    }

    private static void check(final String name, final ProbeTerminal expected,
            final ProbeTerminal actual) {
        checked++;
        if (expected != actual) {
            throw new AssertionError(name + ": expected " + expected + " but was " + actual);
        }
    }

    private static void equal(final String name, final String expected, final String actual) {
        checked++;
        if (!expected.equals(actual)) {
            throw new AssertionError(name + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void contains(final String name, final String actual, final String expected) {
        checked++;
        if (!actual.contains(expected)) {
            throw new AssertionError(name + ": missing <" + expected + "> in <" + actual + ">");
        }
    }

    private static void rejected(final String name, final ProbeTerminal status) {
        checked++;
        try {
            new ProbeAssertions().record("a", "x", "y", status);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(name + ": status <" + status + "> was accepted");
    }
}
