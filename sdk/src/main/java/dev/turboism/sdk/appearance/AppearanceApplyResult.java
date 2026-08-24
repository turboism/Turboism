package dev.turboism.sdk.appearance;


import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of an {@link AppearanceService#apply} call.
 *
 * <p>Failure is reported as a value, never as a thrown exception: {@code REJECTED} means the
 * request was refused (for example on a stale {@code expectedRevision}), {@code UNAVAILABLE} means
 * the host exposes no appearance control, {@code FAILED_RESTORED} means the apply failed but the
 * previous appearance was put back, and {@code FAILED_RESTORE} means even that rollback failed.
 * The compact constructor rejects {@code null} components and a blank diagnostic id.
 *
 * @param outcome what happened to the request
 * @param status the appearance state observed after the attempt
 * @param diagnosticId host diagnostic reference for a non-clean outcome, never blank when present
 */
public record AppearanceApplyResult(
    Outcome outcome,
    AppearanceStatus status,
    Optional<String> diagnosticId
) {
    public AppearanceApplyResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        status = Objects.requireNonNull(status, "status");
        diagnosticId = Objects.requireNonNull(diagnosticId, "diagnosticId").map(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("diagnosticId must not be blank");
            }
            return value;
        });
    }

    public enum Outcome {
        APPLIED,
        NO_CHANGE,
        REJECTED,
        UNAVAILABLE,
        FAILED_RESTORED,
        FAILED_RESTORE
    }
}
