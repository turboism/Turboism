package dev.turboism.tests.plugin;

import java.time.Duration;
import java.util.Optional;

/**
 * Bounded readiness gate one exact-host probe waits on before it judges the session.
 *
 * <p>A probe that judges on its own enable reads a half-built session: sibling plugins have not
 * registered yet, and the Editor has not opened its document. The gate therefore polls one
 * caller-supplied signal until the signal reports ready or the budget expires.</p>
 *
 * <p>The gate always answers a reason. A timeout therefore becomes a recorded {@code BLOCKED}
 * assertion with a cause, never a silent pass and never an immediate failure.</p>
 */
final class AutoMeshProbeReadiness {

    /** One precondition set; empty when every precondition holds, otherwise the blocking reason. */
    @FunctionalInterface
    interface Signal {

        /**
         * Evaluates the precondition set once.
         *
         * @return empty when the session is ready, otherwise the reason it is not
         * @throws Exception when the host refuses a read
         */
        Optional<String> unsatisfied() throws Exception;
    }

    /**
     * The gate verdict.
     *
     * @param ready whether every precondition held inside the budget
     * @param reason the last blocking reason; empty when {@code ready} is true
     * @param waitedMillis how long the gate waited before it decided
     */
    record Outcome(boolean ready, String reason, long waitedMillis) {
        Outcome {
            reason = reason == null ? "" : reason;
        }
    }

    private AutoMeshProbeReadiness() {}

    /**
     * Polls one signal until it reports ready or the budget expires.
     *
     * @param signal the precondition set to poll
     * @param budget the longest accepted wait; a negative budget means one evaluation
     * @param pollMillis the pause between two polls; it must be positive
     * @return the verdict, with the last blocking reason when the budget expired
     * @throws Exception when the signal refuses a read or the probe is interrupted while it waits
     */
    static Outcome await(final Signal signal, final Duration budget, final long pollMillis) throws Exception {
        if (pollMillis <= 0L) {
            throw new IllegalArgumentException("pollMillis must be positive");
        }
        final long started = System.nanoTime();
        final long deadline = started + Math.max(0L, budget.toNanos());
        while (true) {
            final Optional<String> gap = signal.unsatisfied();
            if (gap.isEmpty()) {
                return new Outcome(true, "", elapsedMillis(started));
            }
            if (System.nanoTime() >= deadline) {
                return new Outcome(false, gap.orElseThrow(), elapsedMillis(started));
            }
            Thread.sleep(pollMillis);
        }
    }

    private static long elapsedMillis(final long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }
}
