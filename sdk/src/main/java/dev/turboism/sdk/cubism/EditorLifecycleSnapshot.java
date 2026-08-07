package dev.turboism.sdk.cubism;

import java.time.Instant;
import java.util.Objects;

/** Runtime-visible editor lifecycle state. */
public record EditorLifecycleSnapshot(
    String hostVersion,
    Instant observedAt
) {
    public EditorLifecycleSnapshot {
        hostVersion = Objects.requireNonNull(hostVersion, "hostVersion");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
        if (hostVersion.isBlank()) throw new IllegalArgumentException("hostVersion must not be blank");
    }
}
