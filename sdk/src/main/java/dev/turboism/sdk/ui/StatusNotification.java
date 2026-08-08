package dev.turboism.sdk.ui;

/**
 * Immutable status-bar notification request.
 *
 * <p>{@code presentation} selects how the runtime renders the notification in
 * the host status bar: the ordinary transient {@link Presentation#NOTIFICATION}
 * (severity prefix and tooltip) or a compact resident metric label
 * ({@link Presentation#COMPACT_METRIC}) mounted beside the memory viewer and
 * showing only the raw message.</p>
 */
public record StatusNotification(
    String id,
    String severity,
    String message,
    Presentation presentation
) {

    /**
     * Backward-compatible constructor for ordinary status notifications; the
     * presentation defaults to {@link Presentation#NOTIFICATION} so existing
     * call sites and compiled binaries keep working unchanged.
     */
    public StatusNotification(final String id, final String severity, final String message) {
        this(id, severity, message, Presentation.NOTIFICATION);
    }

    public StatusNotification {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        if (!"INFO".equals(severity) && !"WARNING".equals(severity) && !"ERROR".equals(severity)) {
            throw new IllegalArgumentException("severity must be INFO, WARNING, or ERROR");
        }
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be null or blank");
        }
        if (presentation == null) {
            throw new IllegalArgumentException("presentation must not be null");
        }
    }

    /** How the runtime presents a status notification in the host status bar. */
    public enum Presentation {
        /** Ordinary transient status message with severity prefix and tooltip. */
        NOTIFICATION,
        /**
         * Compact resident metric label (for example {@code CPU 12.3%}) mounted
         * immediately left of the native memory viewer; only the raw message is
         * shown, without severity appearance.
         */
        COMPACT_METRIC
    }
}
