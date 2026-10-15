package dev.turboism.plugin.frameanimation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Key parity across every locale catalog of the frame-animation-import plugin. */
class FrameAnimationImportI18nTest {

    private static final Map<String, String> CATALOGS = Map.of(
            "messages", "META-INF/turboism/i18n/messages.properties",
            "en", "META-INF/turboism/i18n/messages_en.properties",
            "ja", "META-INF/turboism/i18n/messages_ja.properties",
            "ko", "META-INF/turboism/i18n/messages_ko.properties",
            "zh_Hans", "META-INF/turboism/i18n/messages_zh_Hans.properties",
            "zh_Hant", "META-INF/turboism/i18n/messages_zh_Hant.properties");

    @Test
    void everyLocaleCatalogCarriesTheSameKeySet() throws IOException {
        final Set<String> expected = new HashSet<>(readCatalog("messages").stringPropertyNames());
        assertFalse(expected.isEmpty(), "base catalog must not be empty");

        for (final Map.Entry<String, String> catalog : CATALOGS.entrySet()) {
            final Set<String> actual =
                    new HashSet<>(readCatalog(catalog.getKey()).stringPropertyNames());
            assertEquals(expected, actual, "key set mismatch in " + catalog.getKey());
        }
    }

    @Test
    void baseCatalogMatchesBaselineKeysFile() throws IOException {
        final Set<String> base = new HashSet<>(readCatalog("messages").stringPropertyNames());
        final Set<String> baseline = readBaselineKeys();

        assertEquals(baseline, base, "base catalog must match baseline-keys.txt exactly");
    }

    @Test
    void everyCatalogHasNonBlankValues() throws IOException {
        for (final Map.Entry<String, String> catalog : CATALOGS.entrySet()) {
            final Properties properties = readCatalog(catalog.getKey());
            for (final String key : properties.stringPropertyNames()) {
                final String value = properties.getProperty(key);
                assertNotNull(value, "null value for " + key + " in " + catalog.getKey());
                assertFalse(value.isBlank(), "blank value for " + key + " in " + catalog.getKey());
            }
        }
    }

    private static Properties readCatalog(final String name) throws IOException {
        final String path = CATALOGS.get(name);
        assertNotNull(path, "unknown catalog: " + name);
        try (InputStream stream =
                FrameAnimationImportI18nTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "catalog file not found: " + path);
            final Properties properties = new Properties();
            properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            return properties;
        }
    }

    private static Set<String> readBaselineKeys() throws IOException {
        final String path = "META-INF/turboism/i18n/baseline-keys.txt";
        try (InputStream stream =
                FrameAnimationImportI18nTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull(stream, "baseline keys not found: " + path);
            final Set<String> keys = new HashSet<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        keys.add(trimmed);
                    }
                }
            }
            return keys;
        }
    }
}
