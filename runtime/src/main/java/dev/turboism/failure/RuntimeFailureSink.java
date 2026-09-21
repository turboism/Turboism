package dev.turboism.failure;

import java.util.Objects;

/** Internal sink for report-safe runtime failure evidence. */
@FunctionalInterface
public interface RuntimeFailureSink {

    RuntimeFailureSink NOOP = (domain, failure) -> { };

    /**
     * Records one sanitized runtime failure.
     *
     * @param domain the subsystem the failure belongs to
     * @param failure the report-safe failure evidence
     */
    void record(RuntimeFailureDomain domain, RuntimeFailure failure);

    /**
     * @return a sink that discards every record
     */
    static RuntimeFailureSink noop() {
        return NOOP;
    }

    /**
     * @param sink the sink to require
     * @return {@code sink}, for call sites that must fail fast on a missing sink
     * @throws NullPointerException if {@code sink} is null
     */
    static RuntimeFailureSink require(final RuntimeFailureSink sink) {
        return Objects.requireNonNull(sink, "failureSink");
    }
}
