package dev.turboism.automesh;

import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;

/**
 * Turns a contained algorithm failure into the single diagnostic line the native popup can show.
 *
 * <p>The text is user-facing and bounded by {@link AutoMeshResult.Rejected#MAX_REASON_LENGTH}, so a
 * failing algorithm can never exceed the message slot the host renders.</p>
 */
final class AutoMeshFailures {

    private static final String SEPARATOR = ": ";

    private AutoMeshFailures() {}

    /**
     * Describes {@code failure} by its type and message, truncated to the accepted reason length.
     *
     * @param failure the contained failure
     * @return a non-blank diagnostic line
     */
    static String describe(final Throwable failure) {
        final String message = failure.getMessage();
        final String text = message == null || message.isBlank()
                ? failure.getClass().getSimpleName()
                : failure.getClass().getSimpleName() + SEPARATOR + message;
        return text.length() <= AutoMeshResult.Rejected.MAX_REASON_LENGTH
                ? text
                : text.substring(0, AutoMeshResult.Rejected.MAX_REASON_LENGTH);
    }
}
