package dev.turboism.core.runtime;

import java.util.Objects;

public record RuntimeTimerSubmission(
    boolean accepted,
    RuntimeTimerHandle handle
) {
    public RuntimeTimerSubmission {
        handle = Objects.requireNonNull(handle, "handle");
    }
}
