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
}
