package dev.turboism.plugin.parameterbatchtransfer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Key parity across every locale catalog of the plugin. */
class ParameterBatchTransferI18nTest {

    private static final Map<String, String> CATALOGS = Map.of(
        "messages", "META-INF/turboism/i18n/messages.properties",
        "en", "META-INF/turboism/i18n/messages_en.properties",
        "ja", "META-INF/turboism/i18n/messages_ja.properties",
        "zh_Hans", "META-INF/turboism/i18n/messages_zh_Hans.properties",
        "zh_Hant", "META-INF/turboism/i18n/messages_zh_Hant.properties"
    );

    private static final Set<String> REQUIRED_KEYS = Set.of(
        "menu.batchTransfer",
        "dialog.title",
        "dialog.sourceColumn",
        "dialog.targetColumn",
        "dialog.invertColumn",
        "dialog.invertAll",
        "dialog.legend.morph",
        "dialog.legend.combined",
        "dialog.confirm",
        "dialog.cancel",
        "status.noSelection",
        "status.noBoundParameters",
        "status.noChanges",
        "status.applied",
        "status.partial",
        "action.label"
    );

    @Test
    void everyLocaleCatalogCarriesTheSameKeySet() throws IOException {
        final Set<String> expected = new java.util.HashSet<>(readCatalog("messages").stringPropertyNames());
        assertFalse(expected.isEmpty(), "base catalog must not be empty");

        for (final Map.Entry<String, String> catalog : CATALOGS.entrySet()) {
            final Set<String> actual = new java.util.HashSet<>(readCatalog(catalog.getKey()).stringPropertyNames());
            assertEquals(expected, actual, "key set mismatch in " + catalog.getKey());
        }
    }

    @Test
    void baseCatalogCoversTheSpecifiedKeys() throws IOException {
        final Set<String> base = new java.util.HashSet<>(readCatalog("messages").stringPropertyNames());

        for (final String required : REQUIRED_KEYS) {
            assertTrue(base.contains(required), "missing required key " + required);
        }
    }

    @Test
    void everyCatalogHasNonBlankValues() throws IOException {
        for (final Map.Entry<String, String> catalog : CATALOGS.entrySet()) {
            final Properties properties = readCatalog(catalog.getKey());
            for (final String key : properties.stringPropertyNames()) {
                assertFalse(
                    properties.getProperty(key).isBlank(),
                    "blank value for " + key + " in " + catalog.getKey()
                );
            }
        }
    }

    private static Properties readCatalog(final String id) throws IOException {
        final String resource = CATALOGS.get(id);
        try (InputStream stream = ParameterBatchTransferI18nTest.class.getClassLoader()
            .getResourceAsStream(resource)) {
            assertTrue(stream != null, "missing catalog resource " + resource);
            final String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            final Properties properties = new Properties();
            properties.load(new StringReader(text));
            return properties;
        }
    }
}
