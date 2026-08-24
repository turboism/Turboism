package dev.turboism.sdk.appearance;


import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of {@link AppearanceService#restoreOwnedAppearance()}.
 *
 * <p>{@code NO_OWNED_OVERRIDE} means the caller had nothing installed to undo, which is a clean
 * no-op rather than an error; {@code UNAVAILABLE} means the host exposes no appearance control and
 * {@code FAILED_RESTORE} means the override could not be removed. The compact constructor rejects
 * {@code null} components and a blank diagnostic id.
 *
 * @param outcome what happened to the restore
 * @param status the appearance state observed after the attempt
 * @param diagnosticId host diagnostic reference for a non-clean outcome, never blank when present
 */
public record AppearanceRestoreResult(
    Outcome outcome,
    AppearanceStatus status,
    Optional<String> diagnosticId
) {
    public AppearanceRestoreResult {
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
        RESTORED,
        NO_OWNED_OVERRIDE,
        UNAVAILABLE,
        FAILED_RESTORE
    }
}
