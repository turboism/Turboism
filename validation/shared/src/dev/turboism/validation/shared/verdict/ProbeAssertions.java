package dev.turboism.validation.shared.verdict;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered assertion recorder shared by the host-validation probes.
 *
 * <p>Every probe records {@code name}/{@code expected}/{@code actual}/{@code status}
 * tuples here, publishes them through {@link #appendPublication}, and asks
 * {@link #terminal()} for the run verdict. A probe therefore supplies business
 * assertions only: the accepted status set, the counter block, the two record layouts
 * and the publication order live in this type, and {@link ProbeTerminal} owns the
 * verdict rules.</p>
 */
public final class ProbeAssertions {
    /** Longest evidence value kept in one assertion field. */
    public static final int MAX_VALUE_LENGTH = 4000;

    /** Assertion-record layout of a result file. */
    public enum Records {
        /** One {@code assertion.<name>.<field>} group per assertion. */
        NAMED,
        /** One {@code assertion.<index>.<field>} group per assertion, value-escaped. */
        INDEXED
    }

    private final List<ProbeAssertion> assertions = new ArrayList<>();

    /**
     * One recorded assertion.
     *
     * @param name assertion name
     * @param expected normalized expected value
     * @param actual normalized observed value
     * @param status recorded status
     */
    public record ProbeAssertion(String name, String expected, String actual, ProbeTerminal status) {
        /** Whether this assertion contributes to the terminal verdict. */
        public boolean verdictBearing() {
            return status.verdictBearing();
        }
    }

    /**
     * Records one assertion.
     *
     * @param name assertion name
     * @param expected expected value; rendered with {@link #singleLine(Object)}
     * @param actual observed value; rendered with {@link #singleLine(Object)}
     * @param status recorded status
     */
    public void record(
        final String name, final Object expected, final Object actual, final ProbeTerminal status
    ) {
        if (status == null) {
            throw new IllegalArgumentException("assertion status must not be null");
        }
        assertions.add(new ProbeAssertion(
            name, singleLine(expected), singleLine(actual), status));
    }

    /** All recorded assertions in recording order. */
    public List<ProbeAssertion> all() {
        return List.copyOf(assertions);
    }

    /** Recorded assertion count, including evidence-only entries. */
    public int size() {
        return assertions.size();
    }

    /** Number of assertions that decide the terminal verdict. */
    public int verdictCount() {
        int verdicts = 0;
        for (final ProbeAssertion assertion : assertions) {
            if (assertion.verdictBearing()) {
                verdicts++;
            }
        }
        return verdicts;
    }

    /**
     * Terminal verdict of the recorded assertions, using {@link ProbeTerminal#compute}.
     *
     * @return the run verdict
     */
    public ProbeTerminal terminal() {
        final List<ProbeTerminal> statuses = new ArrayList<>(assertions.size());
        for (final ProbeAssertion assertion : assertions) {
            statuses.add(assertion.status());
        }
        return ProbeTerminal.compute(statuses);
    }

    /**
     * Counter line for logs, for example
     * {@code assertions=12 verdictAssertions=10 pass=8 blocked=2 fail=0 skipped=0 info=2}.
     *
     * @return one line of counters without a trailing newline
     */
    public String counts() {
        return "assertions=" + size()
            + " verdictAssertions=" + verdictCount()
            + " pass=" + count(ProbeTerminal.PASS)
            + " blocked=" + count(ProbeTerminal.BLOCKED)
            + " fail=" + count(ProbeTerminal.FAIL)
            + " skipped=" + count(ProbeTerminal.SKIPPED)
            + " info=" + count(ProbeTerminal.INFO);
    }

    /**
     * Appends the complete publication tail in the one order the result consumers read:
     * the counter block, then the assertion records, then the terminal line.
     *
     * <p>{@code scripts/preview/run-cubism-host-validation.sh} reads the counter block as
     * soon as it sees the terminal line and the post-containment evidence gate rechecks the
     * same counters and records, so a probe cannot reorder, duplicate or truncate the tail
     * without going through this method.</p>
     *
     * @param report destination builder holding the probe's own header and evidence lines
     * @param records assertion-record layout to publish
     * @param terminalKey property key of the terminal line, for example {@code status}
     */
    public void appendPublication(
        final StringBuilder report, final Records records, final String terminalKey
    ) {
        appendCounters(report);
        if (records == Records.INDEXED) {
            appendIndexed(report);
        } else {
            appendNamed(report);
        }
        report.append(terminalKey).append('=').append(terminal().name()).append('\n');
    }

    private int count(final ProbeTerminal status) {
        int matches = 0;
        for (final ProbeAssertion assertion : assertions) {
            if (status == assertion.status()) {
                matches++;
            }
        }
        return matches;
    }

    private void appendCounters(final StringBuilder report) {
        report.append("assertions=").append(size()).append('\n')
            .append("assertions.verdict=").append(verdictCount()).append('\n')
            .append("assertions.pass=").append(count(ProbeTerminal.PASS)).append('\n')
            .append("assertions.blocked=").append(count(ProbeTerminal.BLOCKED)).append('\n')
            .append("assertions.fail=").append(count(ProbeTerminal.FAIL)).append('\n')
            .append("assertions.skipped=").append(count(ProbeTerminal.SKIPPED)).append('\n')
            .append("assertions.info=").append(count(ProbeTerminal.INFO)).append('\n');
    }

    private void appendIndexed(final StringBuilder report) {
        for (int index = 0; index < assertions.size(); index++) {
            final ProbeAssertion assertion = assertions.get(index);
            final String prefix = "assertion." + index + '.';
            report.append(prefix).append("name=").append(assertion.name()).append('\n')
                .append(prefix).append("expected=")
                .append(escapeProperty(assertion.expected())).append('\n')
                .append(prefix).append("actual=")
                .append(escapeProperty(assertion.actual())).append('\n')
                .append(prefix).append("status=").append(assertion.status()).append('\n');
        }
    }

    private void appendNamed(final StringBuilder report) {
        for (final ProbeAssertion assertion : assertions) {
            final String prefix = "assertion." + assertion.name() + '.';
            report.append(prefix).append("expected=").append(assertion.expected()).append('\n')
                .append(prefix).append("actual=").append(assertion.actual()).append('\n')
                .append(prefix).append("status=").append(assertion.status()).append('\n');
        }
    }

    /** Escapes one value for a {@code .properties} record of the indexed layout. */
    private static String escapeProperty(final String value) {
        return value.replace("\\", "\\\\").replace("\n", " ").replace("=", "\\=");
    }

    /**
     * Renders one evidence value as a single property line.
     *
     * @param value value to render; {@code null} becomes {@code null}
     * @return the value without line breaks, truncated to {@link #MAX_VALUE_LENGTH}
     */
    public static String singleLine(final Object value) {
        if (value == null) {
            return "null";
        }
        final String text = value.toString().replace('\n', ' ').replace('\r', ' ');
        return text.length() > MAX_VALUE_LENGTH ? text.substring(0, MAX_VALUE_LENGTH) : text;
    }
}
