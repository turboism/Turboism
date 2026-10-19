package dev.turboism.test.concurrent;

import java.time.Duration;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Polling condition waiter for asynchronous test assertions.
 */
public final class Await {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofMillis(10);

    private Await() {}

    /**
     * Polls {@code condition} until it returns {@code true} or the default timeout (5s) elapses.
     *
     * @param condition condition to check
     */
    public static void condition(final BooleanSupplier condition) {
        condition(condition, DEFAULT_TIMEOUT);
    }

    /**
     * Polls {@code condition} until it returns {@code true} or {@code timeout} elapses.
     *
     * @param condition condition to check
     * @param timeout maximum duration to wait
     */
    public static void condition(final BooleanSupplier condition, final Duration timeout) {
        condition(condition, timeout, DEFAULT_POLL_INTERVAL, null);
    }

    /**
     * Polls {@code condition} with a failure message until it returns {@code true} or the default timeout elapses.
     *
     * @param description failure message prefix if timed out
     * @param condition condition to check
     */
    public static void condition(final String description, final BooleanSupplier condition) {
        condition(condition, DEFAULT_TIMEOUT, DEFAULT_POLL_INTERVAL, description);
    }

    /**
     * Polls {@code condition} with a failure message until it returns {@code true} or {@code timeout} elapses.
     *
     * @param description failure message prefix if timed out
     * @param condition condition to check
     * @param timeout maximum duration to wait
     */
    public static void condition(final String description, final BooleanSupplier condition, final Duration timeout) {
        condition(condition, timeout, DEFAULT_POLL_INTERVAL, description);
    }

    /**
     * Alias for {@link #condition(BooleanSupplier)}.
     *
     * @param condition condition to check
     */
    public static void awaitCondition(final BooleanSupplier condition) {
        condition(condition);
    }

    /**
     * Alias for {@link #condition(BooleanSupplier, Duration)}.
     *
     * @param condition condition to check
     * @param timeout maximum duration to wait
     */
    public static void awaitCondition(final BooleanSupplier condition, final Duration timeout) {
        condition(condition, timeout);
    }

    /**
     * Polls {@code condition} until it returns {@code true} or {@code timeout} elapses.
     *
     * @param condition condition to check
     * @param timeout maximum duration to wait
     * @param pollInterval interval between condition evaluations
     * @param description optional failure message prefix if timed out
     */
    public static void condition(
            final BooleanSupplier condition,
            final Duration timeout,
            final Duration pollInterval,
            final String description) {
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(pollInterval, "pollInterval");

        final long deadline = System.nanoTime() + timeout.toNanos();
        final long pollIntervalMillis = Math.max(1, pollInterval.toMillis());

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) {
                final String message = (description != null && !description.isBlank())
                        ? description + " (timed out after " + timeout.toMillis() + " ms)"
                        : "Condition was not met within " + timeout.toMillis() + " ms";
                throw new AssertionError(message);
            }
            try {
                Thread.sleep(pollIntervalMillis);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while awaiting condition", interrupted);
            }
        }
    }
}
