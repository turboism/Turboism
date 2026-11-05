package dev.turboism.validation.shared.verdict;

import java.util.Collection;

/**
 * Assertion statuses shared by the host-validation probes.
 *
 * <p>The set is closed and typed: {@code PASS}, {@code FAIL}, {@code BLOCKED},
 * {@code SKIPPED} and the evidence-only {@code INFO}. A probe records a status through
 * {@link ProbeAssertions#record}, so a mistyped status is a compile error instead of a
 * run-time string that silently leaves the verdict.</p>
 *
 * <p>{@link #compute(Collection)} applies one rule order so every probe answers the same
 * question the same way:</p>
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
 * <p>{@link #INFO} assertions carry diagnostics such as raw host values. They are
 * recorded and archived but never decide the verdict, so they are excluded before the
 * rules above are applied.</p>
 */
public enum ProbeTerminal {
    /** Every verdict-bearing assertion passed. */
    PASS,
    /** At least one verdict-bearing assertion failed. */
    FAIL,
    /** The run could not exercise its assertions or recorded no assertion at all. */
    BLOCKED,
    /** Every executed assertion passed, but the run skipped assertions it owed. */
    SKIPPED,
    /** Evidence-only status: archived with the run, never part of the verdict. */
    INFO;

    /** Whether an assertion with this status decides the terminal verdict. */
    public boolean verdictBearing() {
        return this != INFO;
    }

    /**
     * Returns the terminal verdict of the given statuses, ignoring evidence-only ones.
     *
     * <p>Rules are evaluated in the order documented on this type. An empty collection
     * (or one holding only {@link #INFO} entries) is {@link #BLOCKED}.</p>
     *
     * @param statuses assertion statuses in recording order
     * @return the terminal verdict
     */
    public static ProbeTerminal compute(final Collection<ProbeTerminal> statuses) {
        int verdicts = 0;
        boolean anyBlocked = false;
        boolean anySkipped = false;
        for (final ProbeTerminal status : statuses) {
            if (!status.verdictBearing()) {
                continue;
            }
            if (status == FAIL) {
                return FAIL;
            }
            if (status == BLOCKED) {
                anyBlocked = true;
            } else if (status == SKIPPED) {
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
}
