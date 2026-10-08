package dev.turboism.plugin.commandpalette;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.command.EditorCommand;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Guards the {@code command.<id>} catalog keys: every {@link EditorCommand} constant has a
 * localized name in every shipped locale, all catalogs carry the same keys, and
 * {@code baseline-keys.txt} mirrors the catalog key set.
 */
class CommandPaletteI18nTest {

    private static final Map<String, String> CATALOGS = Map.of(
            "messages", "META-INF/turboism/i18n/messages.properties",
            "en", "META-INF/turboism/i18n/messages_en.properties",
            "ja", "META-INF/turboism/i18n/messages_ja.properties",
            "ko", "META-INF/turboism/i18n/messages_ko.properties",
            "zh_Hans", "META-INF/turboism/i18n/messages_zh_Hans.properties",
            "zh_Hant", "META-INF/turboism/i18n/messages_zh_Hant.properties");

    @Test
    void everyEditorCommandHasANameInEveryLocale() throws IOException {
        for (final Map.Entry<String, String> catalog : CATALOGS.entrySet()) {
            final Properties properties = readCatalog(catalog.getKey());
            for (final EditorCommand command : EditorCommand.values()) {
                final String key = CommandCatalog.key(command);
                assertTrue(properties.containsKey(key), catalog.getKey() + " is missing a name for " + command.name());
                assertFalse(properties.getProperty(key).isBlank(), "blank name for " + key);
            }
        }
    }

    @Test
    void everyLocaleCatalogCarriesTheSameKeys() throws IOException {
        final Set<String> expected = new HashSet<>(readCatalog("messages").stringPropertyNames());
        for (final Map.Entry<String, String> catalog : CATALOGS.entrySet()) {
            assertEquals(
                    expected,
                    new HashSet<>(readCatalog(catalog.getKey()).stringPropertyNames()),
                    "key set mismatch in " + catalog.getKey());
        }
    }

    @Test
    void baselineKeysMatchTheCatalogKeySet() throws IOException {
        final Set<String> baseline = new HashSet<>();
        try (InputStream stream = CommandPaletteI18nTest.class
                .getClassLoader()
                .getResourceAsStream("META-INF/turboism/i18n/baseline-keys.txt")) {
            assertTrue(stream != null, "missing baseline-keys.txt");
            new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .forEach(baseline::add);
        }
        assertEquals(new HashSet<>(readCatalog("messages").stringPropertyNames()), baseline);
    }

    private static Properties readCatalog(final String id) throws IOException {
        try (InputStream stream = CommandPaletteI18nTest.class.getClassLoader().getResourceAsStream(CATALOGS.get(id))) {
            assertTrue(stream != null, "missing catalog " + id);
            final Properties properties = new Properties();
            properties.load(new StringReader(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
            return properties;
        }
    }
}
