package dev.turboism.core.descriptor;

import dev.turboism.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginDescriptorParserTest {

    private final PluginDescriptorParser parser = new PluginDescriptorParser();

    private InputStream toStream(final String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesValidDescriptorWithMultipleEntrypointsAndResources()
        throws DescriptorParseException {
        final String json = """
            {
              "format": "turboism.plugin.meta",
              "schemaVersion": 2,
              "id": "dev.turboism.plugin.demo",
              "name": "Demo Plugin",
              "version": "0.1.0",
              "entrypoints": [
                "dev.turboism.plugin.demo.DemoPlugin",
                "dev.turboism.plugin.demo.ModelHooks"
              ],
              "turboismApi": "[0.1.0,0.2.0)",
              "authors": [{ "name": "Turboism Contributors" }],
              "website": "https://turboism.dev",
              "resources": ["icons/", "themes/"],
              "i18n": {
                "baseName": "META-INF/turboism/i18n/messages",
                "locales": ["base", "en", "zh_Hans"]
              }
            }
            """;

        final PluginDescriptor descriptor = parser.parse(toStream(json));

        assertEquals("dev.turboism.plugin.demo", descriptor.id());
        assertEquals("0.1.0", descriptor.version());
        assertEquals(List.of(
            "dev.turboism.plugin.demo.DemoPlugin",
            "dev.turboism.plugin.demo.ModelHooks"
        ), descriptor.entrypoints());
        assertEquals(List.of("icons/", "themes/"), descriptor.resources());
        assertEquals("META-INF/turboism/i18n/messages", descriptor.i18n().baseName());
        assertEquals(List.of("base", "en", "zh_Hans"), descriptor.i18n().locales());
        assertEquals("https://turboism.dev", descriptor.website().orElseThrow());
    }

    @Test
    void rejectsSchemaVersionOne() {
        final String json = validDescriptor().replace(
            "\"schemaVersion\": 2",
            "\"schemaVersion\": 1"
        );
        assertThrows(DescriptorParseException.class, () -> parser.parse(toStream(json)));
    }

    @Test
    void rejectsLegacyEntrypointObject() {
        final String json = validDescriptor().replace(
            "[\"dev.turboism.plugin.demo.DemoPlugin\"]",
            "{\"plugin\":\"dev.turboism.plugin.demo.DemoPlugin\"}"
        );
        assertThrows(DescriptorParseException.class, () -> parser.parse(toStream(json)));
    }

    @Test
    void rejectsEmptyEntrypoints() {
        final String json = validDescriptor().replace(
            "[\"dev.turboism.plugin.demo.DemoPlugin\"]",
            "[]"
        );
        assertThrows(DescriptorParseException.class, () -> parser.parse(toStream(json)));
    }

    @Test
    void rejectsUndeclaredLegacyHomepage() {
        final String json = validDescriptor().replace(
            "\"website\": \"https://turboism.dev\"",
            "\"homepage\": \"https://turboism.dev\""
        );
        assertThrows(DescriptorParseException.class, () -> parser.parse(toStream(json)));
    }

    private static String validDescriptor() {
        return """
            {
              "format": "turboism.plugin.meta",
              "schemaVersion": 2,
              "id": "dev.turboism.plugin.demo",
              "name": "Demo Plugin",
              "version": "0.1.0",
              "entrypoints": ["dev.turboism.plugin.demo.DemoPlugin"],
              "turboismApi": "[0.1.0,0.2.0)",
              "authors": [{ "name": "Turboism Contributors" }],
              "website": "https://turboism.dev",
              "resources": [],
              "i18n": {
                "baseName": "META-INF/turboism/i18n/messages",
                "locales": []
              }
            }
            """;
    }
}
