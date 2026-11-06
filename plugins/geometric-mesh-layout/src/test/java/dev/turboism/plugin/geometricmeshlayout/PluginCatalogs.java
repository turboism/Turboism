package dev.turboism.plugin.geometricmeshlayout;

import dev.turboism.sdk.i18n.PluginLocalization;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/**
 * Test-only {@link PluginLocalization} over the catalogs this plugin ships.
 *
 * <p>Using the real catalogs keeps the dialog's key set and translations under test without
 * duplicating the runtime's resource-bundle plumbing in the plugin.</p>
 */
final class PluginCatalogs {
    private static final String BASE = "META-INF/turboism/i18n/";

    private PluginCatalogs() {}

    /** Returns a localization backed by {@code messages_<locale>.properties}. */
    static PluginLocalization locale(final String catalogFile) {
        final Properties properties = load(catalogFile);
        return new PluginLocalization() {
            @Override
            public Locale locale() {
                return Locale.ROOT;
            }

            @Override
            public String text(final String key) {
                return properties.getProperty(key);
            }

            @Override
            public String format(final String key, final Object... arguments) {
                return String.valueOf(properties.getProperty(key));
            }

            @Override
            public boolean contains(final String key) {
                return properties.containsKey(key);
            }
        };
    }

    /** Returns the English baseline catalog. */
    static PluginLocalization english() {
        return locale("messages.properties");
    }

    private static Properties load(final String catalogFile) {
        final InputStream stream = Objects.requireNonNull(
                PluginCatalogs.class.getClassLoader().getResourceAsStream(BASE + catalogFile), catalogFile);
        final Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            properties.load(reader);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot read " + catalogFile, failure);
        }
        return properties;
    }
}
