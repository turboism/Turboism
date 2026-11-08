package dev.turboism.plugin.turbomesh.algorithm;

import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;

/**
 * Builds the user-facing text of a refusal.
 *
 * <p>The runtime shows a rejection reason verbatim while the reason code stays the stable machine
 * token, so the text must combine the localized message with the planner detail and still respect
 * the schema limit. Both algorithms share the shape, therefore they share this builder.</p>
 */
final class RejectionText {

    /** Separator between the localized message and the failure detail. */
    private static final String DETAIL_SEPARATOR = ": ";

    private RejectionText() {}

    /**
     * Combines a localized message with a failure detail.
     *
     * @param message the localized user-facing message
     * @param detail the failure detail, which may be {@code null}
     * @return the combined text, truncated to the schema limit
     */
    static String of(final String message, final String detail) {
        final String reason = detail == null || detail.isBlank() ? message : message + DETAIL_SEPARATOR + detail;
        final int limit = AutoMeshResult.Rejected.MAX_REASON_LENGTH;
        return reason.length() <= limit ? reason : reason.substring(0, limit);
    }
}
