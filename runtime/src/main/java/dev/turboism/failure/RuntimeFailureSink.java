package dev.turboism.failure;

import java.util.Objects;

/** Internal sink for report-safe runtime failure evidence. */
@FunctionalInterface
public interface RuntimeFailureSink {

    RuntimeFailureSink NOOP = (domain, failure) -> { };

    void record(RuntimeFailureDomain domain, RuntimeFailure failure);

    static RuntimeFailureSink noop() {
        return NOOP;
    }

    static RuntimeFailureSink require(final RuntimeFailureSink sink) {
        return Objects.requireNonNull(sink, "failureSink");
    }
}
