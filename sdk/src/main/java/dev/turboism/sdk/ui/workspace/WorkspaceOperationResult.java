package dev.turboism.sdk.ui.workspace;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

@PreviewApi
public record WorkspaceOperationResult(
    Outcome outcome,
    WorkspaceStatus status,
    Optional<String> diagnosticCode
) {
    public WorkspaceOperationResult {
        outcome = Objects.requireNonNull(outcome, "outcome");
        status = Objects.requireNonNull(status, "status");
        Objects.requireNonNull(diagnosticCode, "diagnosticCode");
        diagnosticCode = diagnosticCode.map(value -> {
            if (value.isBlank()) {
                throw new IllegalArgumentException("diagnosticCode must not be blank");
            }
            return value;
        });
    }

    public enum Outcome {
        CHANGED,
        NO_CHANGE,
        UNAVAILABLE,
        NOT_FOUND,
        FAILED
    }
}
