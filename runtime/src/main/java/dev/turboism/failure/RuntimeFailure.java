package dev.turboism.failure;

import dev.turboism.internal.Requirements;
import java.util.Comparator;

/** Neutral, immutable failure evidence containing report-safe scalar values only. */
public record RuntimeFailure(
        String code,
        String severity,
        String phase,
        String pluginId,
        String operationId,
        String permissionId,
        String message,
        String relativePath,
        long count) {
    static final Comparator<RuntimeFailure> KEY_ORDER = Comparator.comparing(RuntimeFailure::code)
            .thenComparing(RuntimeFailure::severity)
            .thenComparing(RuntimeFailure::phase)
            .thenComparing(RuntimeFailure::pluginId, Comparator.nullsFirst(String::compareTo))
            .thenComparing(RuntimeFailure::operationId, Comparator.nullsFirst(String::compareTo))
            .thenComparing(RuntimeFailure::permissionId, Comparator.nullsFirst(String::compareTo))
            .thenComparing(RuntimeFailure::message)
            .thenComparing(RuntimeFailure::relativePath, Comparator.nullsFirst(String::compareTo));

    public RuntimeFailure {
        code = Requirements.text(code, "code");
        severity = Requirements.text(severity, "severity");
        phase = Requirements.text(phase, "phase");
        pluginId = optionalText(pluginId, "pluginId");
        operationId = optionalText(operationId, "operationId");
        permissionId = optionalText(permissionId, "permissionId");
        message = Requirements.text(message, "message");
        relativePath = optionalText(relativePath, "relativePath");
        if (count < 1) {
            throw new IllegalArgumentException("count must be positive");
        }
    }

    /**
     * Returns this failure with a different occurrence count, leaving the aggregation key
     * untouched. Used by the collector to fold repeats of one failure into a single entry.
     *
     * @param replacement the new count
     * @return a new record; this one is unchanged
     * @throws IllegalArgumentException if {@code replacement} is less than 1
     */
    public RuntimeFailure withCount(final long replacement) {
        return new RuntimeFailure(
                code, severity, phase, pluginId, operationId, permissionId, message, relativePath, replacement);
    }

    RuntimeFailure key() {
        return count == 1 ? this : withCount(1);
    }

    private static String optionalText(final String value, final String name) {
        if (value == null) {
            return null;
        }
        return Requirements.text(value, name);
    }
}
