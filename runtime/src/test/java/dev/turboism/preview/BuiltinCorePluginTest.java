package dev.turboism.preview;

import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BuiltinCorePluginTest {

    @Test
    void jarSourceUrl_stripsJarPrefixAndEntrySuffix() throws Exception {
        assertEquals(
            "file:/Z:/home/developer/turboism-agent.jar",
            BuiltinCorePlugin.jarSourceUrl(new URL(
                "jar:file:/Z:/home/developer/turboism-agent.jar!/META-INF/turboism/core-plugin.json"
            )).toExternalForm()
        );
    }

    @Test
    void jarSourceUrl_passesThroughNonJarUrls() throws Exception {
        final URL fileUrl = new URL("file:/opt/turboism/classes/");
        assertEquals(fileUrl, BuiltinCorePlugin.jarSourceUrl(fileUrl));
    }
}
