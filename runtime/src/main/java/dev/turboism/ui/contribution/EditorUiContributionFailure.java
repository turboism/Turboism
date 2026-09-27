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

    /** Why reconciling one contribution family failed. */
    public enum Code {
        /** The connected host does not support this family. */
        HOST_UNSUPPORTED,
        /** The host mapping for this family is not verified. */
        MAPPING_NOT_VERIFIED,
        /** The anchor the contribution attaches to was not found. */
        ANCHOR_MISSING,
        /** The provider's apply/reconcile threw. */
        PROVIDER_FAILED,
        /** Removing the provider's previous registration threw. */
        PROVIDER_CLEANUP_FAILED
    }
}
