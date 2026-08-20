package dev.turboism.failure;

import java.util.List;
import java.util.Objects;

/** Immutable runtime-session failure evidence snapshot. */
public record RuntimeFailureSnapshot(
    List<RuntimeFailure> taskFailures,
    List<RuntimeFailure> storageFailures,
    List<RuntimeFailure> configFailures
) {
    private static final RuntimeFailureSnapshot EMPTY = new RuntimeFailureSnapshot(
        List.of(),
        List.of(),
        List.of()
    );

    public RuntimeFailureSnapshot {
        taskFailures = List.copyOf(Objects.requireNonNull(taskFailures, "taskFailures"));
        storageFailures = List.copyOf(Objects.requireNonNull(storageFailures, "storageFailures"));
        configFailures = List.copyOf(Objects.requireNonNull(configFailures, "configFailures"));
    }

    /**
     * @return the shared snapshot with no failures in any domain; safe to share because the record
     *     and all three lists are immutable
     */
    public static RuntimeFailureSnapshot empty() {
        return EMPTY;
    }
}
