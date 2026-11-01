package dev.turboism.validation.shared.verdict;

import java.util.Collection;

/**
 * Terminal verdicts shared by the host-validation probes.
 *
 * <p>The terminal set is fixed: {@code PASS}, {@code FAIL}, {@code BLOCKED} and
 * {@code SKIPPED}. {@link #compute(Collection)} applies one rule order so every probe
 * answers the same question the same way:</p>
 *
 * <ol>
 *   <li>any {@code FAIL} assertion makes the run {@code FAIL};</li>
 *   <li>otherwise any {@code BLOCKED} assertion makes it {@code BLOCKED};</li>
 *   <li>otherwise zero verdict-bearing assertions make it {@code BLOCKED}, because a run
 *       that asserted nothing proved nothing;</li>
 *   <li>otherwise any {@code SKIPPED} assertion makes it {@code SKIPPED}, because a run
 *       that skipped part of its contract cannot claim {@code PASS};</li>
 *   <li>otherwise every assertion passed and the run is {@code PASS}.</li>
 * </ol>
 *
 * <p>{@link #EVIDENCE_ONLY} assertions carry diagnostics such as raw host values. They are
 * recorded and archived but never decide the terminal verdict, so they are excluded before
 * the rules above are applied.</p>
 *
 * <p>Any other status string is rejected by {@link #parse(String)}. A probe that records a
 * mistyped or unknown status fails instead of silently passing.</p>
 */
public enum ProbeTerminal {
    /** Every verdict-bearing assertion passed. */
    PASS,
    /** At least one verdict-bearing assertion failed. */
    FAIL,
    /** The run could not exercise its assertions or recorded no assertion at all. */
    BLOCKED,
    /** Every executed assertion passed, but the run skipped assertions it owed. */
    SKIPPED;

    /**
     * Evidence-only assertion status. Diagnostics recorded under this status are archived
     * with the run and never participate in {@link #compute(Collection)}.
     */
    public static final String EVIDENCE_ONLY = "INFO";

    /**
     * Returns the terminal verdict for a collection of assertion statuses.
     *
     * <p>Rules are evaluated in the order documented on this type. {@link #EVIDENCE_ONLY}
     * entries are ignored; an empty collection (or one holding only evidence entries) is
     * {@link #BLOCKED}.</p>
     *
     * @param statuses assertion statuses in recording order
     * @return the terminal verdict
     * @throws IllegalArgumentException when a status is not part of the accepted set
     */
    public static ProbeTerminal compute(final Collection<String> statuses) {
        int verdicts = 0;
        boolean anyBlocked = false;
        boolean anySkipped = false;
        for (final String status : statuses) {
            if (EVIDENCE_ONLY.equals(status)) {
                continue;
            }
            final ProbeTerminal parsed = parse(status);
            if (parsed == FAIL) {
                return FAIL;
            }
            if (parsed == BLOCKED) {
                anyBlocked = true;
            } else if (parsed == SKIPPED) {
                anySkipped = true;
            }
            verdicts++;
        }
        if (anyBlocked) {
            return BLOCKED;
        }
        if (verdicts == 0) {
            return BLOCKED;
        }
        return anySkipped ? SKIPPED : PASS;
    }

    /**
     * Parses one assertion status.
     *
     * @param status status token to parse; {@link #EVIDENCE_ONLY} is accepted because probes
     *     record it, but it has no terminal verdict of its own
     * @return the matching terminal, or {@code null} for {@link #EVIDENCE_ONLY}
     * @throws IllegalArgumentException when the token is not part of the accepted set
     */
    public static ProbeTerminal parse(final String status) {
        if (status == null) {
            throw new IllegalArgumentException("assertion status must not be null");
        }
        if (EVIDENCE_ONLY.equals(status)) {
            return null;
        }
        for (final ProbeTerminal candidate : values()) {
            if (candidate.name().equals(status)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException("unknown assertion status: " + status);
    }
}
