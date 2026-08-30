package dev.turboism.preview;

import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Monotonic, low-volume timing records for diagnosing exact-host startup delays. */
final class StartupPhaseTimer {

    private final LongSupplier ticker;
    private final long startedAt;
    private long previous;

    StartupPhaseTimer(final LongSupplier ticker) {
        this.ticker = Objects.requireNonNull(ticker, "ticker");
        startedAt = ticker.getAsLong();
        previous = startedAt;
    }

    void completed(final String phase, final Consumer<String> logger) {
        final String name = Objects.requireNonNull(phase, "phase").trim();
        if (name.isEmpty()) throw new IllegalArgumentException("phase must not be blank");
        final long now = ticker.getAsLong();
        final long phaseNanos = Math.max(0L, now - previous);
        final long totalNanos = Math.max(0L, now - startedAt);
        previous = now;
        Objects.requireNonNull(logger, "logger").accept(
            "Startup phase " + name + " completed in " + millis(phaseNanos)
                + " ms (total " + millis(totalNanos) + " ms)"
        );
    }

    private static long millis(final long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(nanos);
    }
}
