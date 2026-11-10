package dev.turboism.automesh.host;

import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Objects;
import java.util.ResourceBundle;

/**
 * Localized texts of the Auto Mesh notices the guarded native entry reports.
 *
 * <p>The catalog lives beside the guard so the runtime renders the same words on every host
 * version. A missing key falls back to the English baseline, because a notice the user cannot read
 * is still better than a silent refusal.</p>
 */
public final class AutoMeshMessages {

    private static final String BUNDLE = "dev.turboism.automesh.host.messages";

    /**
     * Catalog lookup that never falls back to the JVM default locale.
     *
     * <p>Without it an unsupported runtime locale would render the machine's own language instead
     * of the English baseline, which makes a notice unreadable for the user who selected that
     * locale.</p>
     */
    private static final ResourceBundle.Control NO_LOCALE_FALLBACK =
            ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES);

    private static final String KEY_APPLIED = "automesh.notice.applied";
    private static final String KEY_REJECTED = "automesh.notice.rejected";
    private static final String KEY_CANCELLED = "automesh.notice.cancelled";
    private static final String KEY_FAILED = "automesh.notice.failed";
    private static final String KEY_RUN_FAILED = "automesh.notice.run-failed";
    private static final String FALLBACK_APPLIED = "Auto Mesh applied to {0} ArtMesh(s).";
    private static final String FALLBACK_REJECTED = "Auto Mesh was rejected: {0}";
    private static final String FALLBACK_CANCELLED = "Auto Mesh was cancelled.";
    private static final String FALLBACK_FAILED = "Auto Mesh failed: {0}";
    private static final String FALLBACK_RUN_FAILED = "Auto Mesh did not complete.";

    private final ResourceBundle bundle;

    private AutoMeshMessages(final ResourceBundle bundle) {
        this.bundle = bundle;
    }

    /**
     * Loads the catalog of one locale.
     *
     * @param locale the effective runtime locale; {@code null} resolves to English
     * @return the catalog of that locale
     */
    public static AutoMeshMessages forLocale(final Locale locale) {
        final Locale requested = locale == null ? Locale.ENGLISH : locale;
        try {
            return new AutoMeshMessages(ResourceBundle.getBundle(BUNDLE, requested, NO_LOCALE_FALLBACK));
        } catch (MissingResourceException unavailable) {
            return new AutoMeshMessages(null);
        }
    }

    /**
     * @param meshCount the number of ArtMeshes the committed transaction replaced
     * @return the localized success notice
     */
    public String applied(final int meshCount) {
        return text(KEY_APPLIED, FALLBACK_APPLIED, meshCount);
    }

    /**
     * @param reason the algorithm's own rejection text
     * @return the localized rejection notice
     */
    public String rejected(final String reason) {
        return text(KEY_REJECTED, FALLBACK_REJECTED, reason);
    }

    /** @return the localized cancellation notice */
    public String cancelled() {
        return text(KEY_CANCELLED, FALLBACK_CANCELLED);
    }

    /**
     * @param reason the fault summary of the failed run
     * @return the localized failure notice
     */
    public String failed(final String reason) {
        return text(KEY_FAILED, FALLBACK_FAILED, reason);
    }

    /** @return the localized notice of a run whose completion never reported a result */
    public String runFailed() {
        return text(KEY_RUN_FAILED, FALLBACK_RUN_FAILED);
    }

    private String text(final String key, final String fallback, final Object... arguments) {
        final String pattern = patternOf(key, fallback);
        return new java.text.MessageFormat(pattern, Locale.ROOT).format(arguments);
    }

    private String patternOf(final String key, final String fallback) {
        if (bundle == null) {
            return fallback;
        }
        try {
            return Objects.requireNonNullElse(bundle.getString(key), fallback);
        } catch (MissingResourceException absent) {
            return fallback;
        }
    }
}
