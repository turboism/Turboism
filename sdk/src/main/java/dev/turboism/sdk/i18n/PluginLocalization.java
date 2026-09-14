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

    /** Returns the locale this catalog resolved to. */
    Locale locale();

    /**
     * Returns the localized text for {@code key}, falling back according to the runtime's
     * catalog fallback rules.
     */
    String text(String key);

    /** Returns the localized text for {@code key} with {@code arguments} applied. */
    String format(String key, Object... arguments);

    /** Returns whether {@code key} is present in this catalog. */
    boolean contains(String key);
}
