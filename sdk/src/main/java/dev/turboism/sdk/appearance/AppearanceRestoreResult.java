package dev.turboism.sdk.appearance;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

@PreviewApi
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
