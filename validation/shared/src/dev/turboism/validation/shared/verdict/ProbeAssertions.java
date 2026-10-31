package dev.turboism.validation.shared.verdict;

import java.util.ArrayList;
import java.util.List;

/**
 * Ordered assertion recorder shared by the host-validation probes.
 *
 * <p>Every probe records {@code name}/{@code expected}/{@code actual}/{@code status} tuples
 * through this recorder, publishes them in one of two stable property layouts and asks
 * {@link #terminal()} for the run verdict. The recorder validates the status at record time:
 * only {@code PASS}, {@code FAIL}, {@code BLOCKED}, {@code SKIPPED} and the evidence-only
 * {@link ProbeTerminal#EVIDENCE_ONLY} status are accepted, so a typo fails the run instead of
 * disappearing from the verdict.</p>
 */
public final class ProbeAssertions {
    /** Longest evidence value kept in one assertion field. */
    public static final int MAX_VALUE_LENGTH = 4000;

    private final List<ProbeAssertion> assertions = new ArrayList<>();

    /**
     * One recorded assertion.
     *
     * @param name assertion name
     * @param expected normalized expected value
     * @param actual normalized observed value
     * @param status one of the accepted status tokens
     */
    public record ProbeAssertion(String name, String expected, String actual, String status) {
        /** Whether this assertion contributes to the terminal verdict. */
        public boolean verdictBearing() {
            return !ProbeTerminal.EVIDENCE_ONLY.equals(status);
        }
    }

    /**
     * Records one assertion.
     *
     * @param name assertion name
     * @param expected expected value; rendered with {@link #singleLine(Object)}
     * @param actual observed value; rendered with {@link #singleLine(Object)}
     * @param status accepted status token
     * @throws IllegalArgumentException when the status is not part of the accepted set
     */
    public void record(
        final String name, final Object expected, final Object actual, final String status
    ) {
        ProbeTerminal.parse(status);
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
     * Counts recorded assertions with the given status.
     *
     * @param status accepted status token
     * @return matching assertion count
     */
    public int count(final String status) {
        ProbeTerminal.parse(status);
        int matches = 0;
        for (final ProbeAssertion assertion : assertions) {
            if (status.equals(assertion.status())) {
                matches++;
            }
        }
        return matches;
    }

    /**
     * Terminal verdict of the recorded assertions, using {@link ProbeTerminal#compute}.
     *
     * @return the run verdict
     */
    public ProbeTerminal terminal() {
        final List<String> statuses = new ArrayList<>(assertions.size());
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
            + " pass=" + count(ProbeTerminal.PASS.name())
            + " blocked=" + count(ProbeTerminal.BLOCKED.name())
            + " fail=" + count(ProbeTerminal.FAIL.name())
            + " skipped=" + count(ProbeTerminal.SKIPPED.name())
            + " info=" + count(ProbeTerminal.EVIDENCE_ONLY);
    }

    /**
     * Appends one {@code key=value} counter line per counter, so result files carry the
     * assertion counts the post-containment evidence gate rechecks.
     *
     * @param report destination builder
     */
    public void appendCounters(final StringBuilder report) {
        report.append("assertions=").append(size()).append('\n')
            .append("assertions.verdict=").append(verdictCount()).append('\n')
            .append("assertions.pass=").append(count(ProbeTerminal.PASS.name())).append('\n')
            .append("assertions.blocked=").append(count(ProbeTerminal.BLOCKED.name())).append('\n')
            .append("assertions.fail=").append(count(ProbeTerminal.FAIL.name())).append('\n')
            .append("assertions.skipped=").append(count(ProbeTerminal.SKIPPED.name())).append('\n')
            .append("assertions.info=").append(count(ProbeTerminal.EVIDENCE_ONLY)).append('\n');
    }

    /**
     * Appends the assertions as {@code assertion.<index>.<field>} properties, the layout the
     * animation-timeline probe publishes.
     *
     * @param report destination builder
     * @param escapeValues whether to escape values as {@code .properties} strings
     */
    public void appendIndexed(final StringBuilder report, final boolean escapeValues) {
        for (int index = 0; index < assertions.size(); index++) {
            final ProbeAssertion assertion = assertions.get(index);
            final String expected = escapeValues
                ? escapeProperty(assertion.expected()) : assertion.expected();
            final String actual = escapeValues
                ? escapeProperty(assertion.actual()) : assertion.actual();
            report.append("assertion.").append(index).append(".name=").append(assertion.name())
                .append('\n')
                .append("assertion.").append(index).append(".expected=")
                .append(expected).append('\n')
                .append("assertion.").append(index).append(".actual=")
                .append(actual).append('\n')
                .append("assertion.").append(index).append(".status=")
                .append(assertion.status()).append('\n');
        }
    }

    /**
     * Escapes one value for a {@code .properties} record, matching the escaping the
     * animation-timeline probe published before the terminal rule moved here.
     *
     * @param value value to escape
     * @return the escaped value
     */
    public static String escapeProperty(final String value) {
        return value.replace("\\", "\\\\").replace("\n", " ").replace("=", "\\=");
    }

    /**
     * Appends the assertions as {@code assertion.<name>.<field>} properties, the layout the
     * atlas-polygon, clipmask-viewer and parameter-batch-transfer probes publish.
     *
     * @param report destination builder
     */
    public void appendNamed(final StringBuilder report) {
        for (final ProbeAssertion assertion : assertions) {
            report.append("assertion.").append(assertion.name()).append(".expected=")
                .append(assertion.expected()).append('\n')
                .append("assertion.").append(assertion.name()).append(".actual=")
                .append(assertion.actual()).append('\n')
                .append("assertion.").append(assertion.name()).append(".status=")
                .append(assertion.status()).append('\n');
        }
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
