package dev.turboism.sdk.cubism.event;


import java.util.Objects;
import java.util.Optional;

/**
 * Immutable correlation value shared by all phases of one semantic operation.
 *
 * @param sequence runtime-local monotonically increasing correlation sequence
 * @param operation typed semantic operation
 * @param origin best-known source, or {@link CubismOperationOrigin#UNKNOWN}
 * @param subjectId optional Turboism-owned project, document, model, object, or command identity
 */
public record CubismOperationEvent(
    long sequence,
    CubismOperation operation,
    CubismOperationOrigin origin,
    Optional<String> subjectId
) {
    /** Validates and normalizes one operation event. */
    public CubismOperationEvent {
        if (sequence < 1L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        operation = Objects.requireNonNull(operation, "operation");
        origin = Objects.requireNonNull(origin, "origin");
        subjectId = Objects.requireNonNull(subjectId, "subjectId")
            .map(value -> requireSubjectId(value));
    }

    private static String requireSubjectId(final String value) {
        final String actual = Objects.requireNonNull(value, "subjectId value");
        if (actual.isBlank()) {
            throw new IllegalArgumentException("subjectId must not be blank");
        }
        return actual;
    }
}
