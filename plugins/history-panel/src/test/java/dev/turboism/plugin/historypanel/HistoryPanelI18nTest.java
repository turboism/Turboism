package dev.turboism.plugin.historypanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;

/** Relation-phrase placeholder coverage for every History Panel locale catalog. */
class HistoryPanelI18nTest {
    private static final Map<String, String> CATALOGS = Map.of(
            "messages", "META-INF/turboism/i18n/messages.properties",
            "en", "META-INF/turboism/i18n/messages_en.properties",
            "ja", "META-INF/turboism/i18n/messages_ja.properties",
            "ko", "META-INF/turboism/i18n/messages_ko.properties",
            "zh_Hans", "META-INF/turboism/i18n/messages_zh_Hans.properties",
            "zh_Hant", "META-INF/turboism/i18n/messages_zh_Hant.properties");

    @Test
    void relationPhrasesCarryBothPatternMarkersInEveryLocale() throws IOException {
        for (final Map.Entry<String, String> catalog : CATALOGS.entrySet()) {
            final Properties properties = readCatalog(catalog.getKey());
            for (final String key : properties.stringPropertyNames()) {
                if (!key.startsWith("history.relation.")) {
                    continue;
                }
                final String value = properties.getProperty(key);
                assertTrue(value.contains("{0}"), key + " must carry the particle marker");
                assertEquals(1, value.split("\\{1\\}", -1).length - 1, key + " must carry exactly one parent marker");
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
}
