package dev.turboism.sdk.ui.workspace;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.Optional;

/**
 * Outcome of a workspace mutation, together with the workspace state observed afterwards.
 *
 * <p>Workspace operations do not throw to report host refusal: an unavailable host or an unknown
 * workspace is reported as an {@link Outcome} value with an optional machine-readable diagnostic
 * code. A present {@code diagnosticCode} is never blank.
 *
 * @param outcome        what the operation did, or why it did nothing
 * @param status         the workspace status observed after the operation
 * @param diagnosticCode stable machine-readable reason code, empty when there is nothing to report
 */
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

    /**
     * What a workspace operation actually achieved.
     */
    @PreviewApi
    public enum Outcome {
        CHANGED,
        NO_CHANGE,
        UNAVAILABLE,
        NOT_FOUND,
        FAILED
    }
}
