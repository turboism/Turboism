package dev.turboism.ui.contribution;

import dev.turboism.ui.host.EditorUiFamily;

import java.util.Objects;

/** Sanitized contribution reconciliation failure retained by runtime policy. */
public record EditorUiContributionFailure(
    Code code,
    EditorUiFamily family,
    String message
) {
    public EditorUiContributionFailure {
        code = Objects.requireNonNull(code, "code");
        family = Objects.requireNonNull(family, "family");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    public enum Code {
        HOST_UNSUPPORTED,
        MAPPING_NOT_VERIFIED,
        ANCHOR_MISSING,
        PROVIDER_FAILED,
        PROVIDER_CLEANUP_FAILED
    }
}
