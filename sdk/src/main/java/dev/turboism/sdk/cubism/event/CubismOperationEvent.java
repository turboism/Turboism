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
 * @param label optional human-readable name of the operation, for example the localizable native
 *              edit name a Cubism Editor action was started with. It is presentation only: a label
 *              is never an identity and is never proof of what an operation changed
 */
public record CubismOperationEvent(
    long sequence,
    CubismOperation operation,
    CubismOperationOrigin origin,
    Optional<String> subjectId,
    Optional<String> label
) {
    /** Validates and normalizes one operation event. */
    public CubismOperationEvent {
        if (sequence < 1L) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        operation = Objects.requireNonNull(operation, "operation");
        origin = Objects.requireNonNull(origin, "origin");
        subjectId = Objects.requireNonNull(subjectId, "subjectId")
            .map(value -> requireText(value, "subjectId"));
        label = Objects.requireNonNull(label, "label").map(value -> requireText(value, "label"));
    }

    private static String requireText(final String value, final String name) {
        final String actual = Objects.requireNonNull(value, name + " value");
        if (actual.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return actual;
    }
}
