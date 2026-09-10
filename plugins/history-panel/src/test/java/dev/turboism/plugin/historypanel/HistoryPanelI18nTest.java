package dev.turboism.plugin.historypanel;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Key parity and fallback coverage for every History Panel locale catalog. */
class HistoryPanelI18nTest {
    private static final Map<String, String> CATALOGS = Map.of(
        "messages", "META-INF/turboism/i18n/messages.properties",
        "en", "META-INF/turboism/i18n/messages_en.properties",
        "ja", "META-INF/turboism/i18n/messages_ja.properties",
        "ko", "META-INF/turboism/i18n/messages_ko.properties",
        "zh_Hans", "META-INF/turboism/i18n/messages_zh_Hans.properties",
        "zh_Hant", "META-INF/turboism/i18n/messages_zh_Hant.properties"
    );

    private static final Set<String> RICH_ROW_KEYS = Set.of(
        "history.entry.action.set",
        "history.entry.action.add",
        "history.entry.action.remove",
        "history.icon.art-mesh",
        "history.icon.part",
        "history.icon.rotation-deformer",
        "history.icon.warp-deformer",
        "history.relation.deformer-parent.detach.infix",
        "history.relation.deformer-parent.detach.suffix",
        "history.relation.deformer-parent.set.infix",
        "history.relation.deformer-parent.set.suffix",
        "history.relation.part-membership.detach.infix",
        "history.relation.part-membership.detach.suffix",
        "history.relation.part-membership.join.infix",
        "history.relation.part-membership.join.suffix"
    );

    private static final Set<String> ALLOW_BLANK_FRAGMENT_KEYS = Set.of(
        "history.relation.part-membership.join.suffix",
        "history.relation.deformer-parent.detach.suffix",
        "history.relation.part-membership.detach.suffix"
    );

    @Test
    void everyLocaleCatalogCarriesTheSameKeysAndRichRowKeys() throws IOException {
        final Set<String> expected = new HashSet<>(readCatalog("messages").stringPropertyNames());
        assertFalse(expected.isEmpty(), "base catalog must not be empty");
        assertTrue(expected.containsAll(RICH_ROW_KEYS), "base catalog must define rich-row keys");

        for (final Map.Entry<String, String> catalog : CATALOGS.entrySet()) {
            final Set<String> actual = new HashSet<>(readCatalog(catalog.getKey()).stringPropertyNames());
            assertEquals(expected, actual, "key set mismatch in " + catalog.getKey());
        }
    }

    @Test
    void baselineKeysMatchTheCatalogKeySet() throws IOException {
        final Set<String> baseline = readBaselineKeys();
        final Set<String> catalog = new HashSet<>(readCatalog("messages").stringPropertyNames());
        assertEquals(catalog, baseline);
        assertTrue(baseline.containsAll(RICH_ROW_KEYS), "baseline must define rich-row keys");
    }

    @Test
    void everyCatalogHasNonBlankValues() throws IOException {
        for (final Map.Entry<String, String> catalog : CATALOGS.entrySet()) {
            final Properties properties = readCatalog(catalog.getKey());
            for (final String key : properties.stringPropertyNames()) {
                if (!ALLOW_BLANK_FRAGMENT_KEYS.contains(key)) {
                    assertFalse(
                        properties.getProperty(key).isBlank(),
                        "blank value for " + key + " in " + catalog.getKey()
                    );
                }
            }
        }
    }

    private static Properties readCatalog(final String id) throws IOException {
        final String resource = CATALOGS.get(id);
        try (InputStream stream = HistoryPanelI18nTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(stream != null, "missing catalog resource " + resource);
            final Properties properties = new Properties();
            properties.load(new StringReader(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
            return properties;
        }
    }

    private static Set<String> readBaselineKeys() throws IOException {
        final String resource = "META-INF/turboism/i18n/baseline-keys.txt";
        try (InputStream stream = HistoryPanelI18nTest.class.getClassLoader().getResourceAsStream(resource)) {
            assertTrue(stream != null, "missing baseline resource " + resource);
            final Set<String> keys = new HashSet<>();
            for (final String line : new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                if (!line.isBlank()) {
                    keys.add(line.strip());
                }
            }
            return keys;
        }
    }
}
