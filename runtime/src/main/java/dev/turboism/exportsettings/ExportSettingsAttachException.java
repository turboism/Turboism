package dev.turboism.exportsettings;

/**
 * The single stable failure type of the inert export-settings attachment backend.
 *
 * <p>Every rejection of {@link ExportSettingsAttachBackend} — argument validation,
 * session-state violation, EDT interruption, and contained boundary {@link Throwable}s —
 * surfaces as this one type with a stable message identity and, where available, the
 * original cause. Callers never observe host or Swing exceptions directly.</p>
 */
public final class ExportSettingsAttachException extends RuntimeException {

    public ExportSettingsAttachException(final String message) {
        super(message);
    }

    public ExportSettingsAttachException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
