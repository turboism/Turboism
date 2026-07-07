package dev.turboism.core.descriptor;

import dev.turboism.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PluginDescriptorParserTest {

    private final PluginDescriptorParser parser = new PluginDescriptorParser();

    private InputStream toStream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void parsesValidDescriptor() throws DescriptorParseException {
        String json = """
            {
              "format": "turboism.plugin.meta",
              "schemaVersion": 1,
              "id": "dev.turboism.plugin.demo",
              "name": "Demo Plugin",
              "version": "0.1.0",
              "entrypoints": { "plugin": "dev.turboism.plugin.demo.DemoPlugin" },
              "turboismApi": "[0.1.0,0.2.0)"
            }
            """;
        PluginDescriptor d = parser.parse(toStream(json));
        assertEquals("dev.turboism.plugin.demo", d.id());
        assertEquals("0.1.0", d.version());
        assertEquals("dev.turboism.plugin.demo.DemoPlugin", d.entrypoints().get("plugin"));
    }

    @Test
    void rejectsMissingFormat() {
        String json = """
            { "schemaVersion": 1, "id": "x", "name": "X", "version": "0.1.0", "entrypoints": {}, "turboismApi": "0.1.0" }
            """;
        assertThrows(DescriptorParseException.class, () -> parser.parse(toStream(json)));
    }

    @Test
    void rejectsBadSchemaVersion() {
        String json = """
            { "format": "turboism.plugin.meta", "schemaVersion": 2, "id": "x", "name": "X", "version": "0.1.0", "entrypoints": { "plugin": "X" }, "turboismApi": "0.1.0" }
            """;
        assertThrows(DescriptorParseException.class, () -> parser.parse(toStream(json)));
    }

    @Test
    void rejectsMissingEntrypoint() {
        String json = """
            { "format": "turboism.plugin.meta", "schemaVersion": 1, "id": "x", "name": "X", "version": "0.1.0", "entrypoints": {}, "turboismApi": "0.1.0" }
            """;
        assertThrows(DescriptorParseException.class, () -> parser.parse(toStream(json)));
    }
}
