package dev.turboism.sdk.i18n;

import java.util.Locale;

/**
 * Plugin-scoped localization catalog.
 *
 * <p>The runtime owns locale selection, catalog isolation, fallback, formatting,
 * and diagnostics. Implementations must not expose resource-bundle or runtime
 * implementation types through this API.</p>
 */
public interface PluginLocalization {

    Locale locale();

    String text(String key);

    String format(String key, Object... arguments);

    boolean contains(String key);

    /**
     * Reports whether a live runtime surface backs this instance.
     *
     * @return {@code false} only for the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    static PluginLocalization unavailable() {
        return Unavailable.INSTANCE;
    }

    enum Unavailable implements PluginLocalization {
        INSTANCE;

        @Override public boolean isAvailable() {
            return false;
        }

        @Override public Locale locale() {
            throw unavailable();
        }

        @Override public String text(final String key) {
            throw unavailable();
        }

        @Override public String format(final String key, final Object... arguments) {
            throw unavailable();
        }

        @Override public boolean contains(final String key) {
            throw unavailable();
        }

        private static UnsupportedOperationException unavailable() {
            return new UnsupportedOperationException("localization service is not available");
        }
    }
}
