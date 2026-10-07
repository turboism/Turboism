package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.json.Json;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class McpStdioBridgeTest {

    @TempDir
    Path stateDir;

    @Test
    void publishesABridgeSourceBoundToTheStateDirectory() throws Exception {
        final Path bridge = McpStdioBridge.publish(stateDir);

        assertEquals(stateDir.resolve(McpStdioBridge.FILE_NAME), bridge);
        final String source = Files.readString(bridge, StandardCharsets.UTF_8);
        assertTrue(source.contains(stateDir.toAbsolutePath().toString().replace("\\", "\\\\")));
        assertTrue(source.contains("mcp.token"));
        assertTrue(source.contains("mcp-connection.json"));
        assertTrue(source.contains("Authorization\", \"Bearer "));
        assertTrue(source.contains("class TurboismMcpBridge"));

        // An unchanged source is not republished.
        final long written = Files.getLastModifiedTime(bridge).toMillis();
        assertEquals(bridge, McpStdioBridge.publish(stateDir));
        assertEquals(written, Files.getLastModifiedTime(bridge).toMillis());
    }

    @Test
    void publishedBridgeCompilesAsAStandaloneSourceFile() throws Exception {
        final javax.tools.JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        org.junit.jupiter.api.Assumptions.assumeTrue(compiler != null, "system Java compiler unavailable");
        final Path bridge = McpStdioBridge.publish(stateDir);
        assertEquals(0, compiler.run(null, null, null, bridge.toString()));
    }

    @Test
    void clientConfigTargetsThePublishedBridge() {
        final Map<String, Object> config = McpStdioBridge.clientConfig(stateDir);
        assertEquals("java", config.get("command"));
        assertEquals(
                List.of(stateDir.resolve(McpStdioBridge.FILE_NAME)
                        .toAbsolutePath()
                        .toString()),
                config.get("args"));
        final String json = McpStdioBridge.clientConfigJson(stateDir);
        final Map<String, ?> parsed = Json.parseObject(json.getBytes(StandardCharsets.UTF_8));
        assertEquals(config, parsed);
        assertTrue(json.startsWith("{") && json.endsWith("}"));
    }
}
