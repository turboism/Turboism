package dev.turboism.sdk.performance;

import dev.turboism.sdk.event.EventBus;

import java.util.Objects;

/**
 * Runtime-owned latest-only performance observation.
 *
 * <p>Sampling producers may coalesce intermediate snapshots when an earlier
 * publication is still pending. {@link #coalescedSamples()} reports how many
 * snapshots were replaced since the preceding delivered observation.</p>
 */
public record PerformanceSampleEvent(
    PerformanceSnapshot snapshot,
    long coalescedSamples
) implements EventBus.TurboismEvent {

    public PerformanceSampleEvent {
        snapshot = Objects.requireNonNull(snapshot, "snapshot");
        if (coalescedSamples < 0L) {
            throw new IllegalArgumentException("coalescedSamples must not be negative");
        }
    }
}
