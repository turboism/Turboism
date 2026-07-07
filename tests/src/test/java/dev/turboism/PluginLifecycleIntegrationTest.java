package dev.turboism;

import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class PluginLifecycleIntegrationTest {

    @Test
    void descriptorParsingSpansSdkAndRuntime() throws Exception {
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

        PluginDescriptorParser parser = new PluginDescriptorParser();
        PluginDescriptor descriptor = parser.parse(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));

        assertEquals("dev.turboism.plugin.demo", descriptor.id());
        assertEquals("0.1.0", descriptor.version());
        assertTrue(descriptor.entrypoints().containsKey("plugin"));
    }
}
