package dev.turboism.sdk.appearance;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

@PreviewApi
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

    @PreviewApi
    public enum Outcome {
        APPLIED,
        NO_CHANGE,
        REJECTED,
        UNAVAILABLE,
        FAILED_RESTORED,
        FAILED_RESTORE
    }
}
