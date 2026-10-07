package dev.turboism.plugin.mcp;

import dev.turboism.protocol.json.StrictJson;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Publishes the stdio↔HTTP bridge source next to the access token in the plugin
 * state directory. MCP clients spawn {@code java TurboismMcpBridge.java}; the
 * bridge forwards each stdin JSON-RPC line to the loopback endpoint carrying the
 * persisted bearer token, so users never handle credentials themselves.
 */
final class McpStdioBridge {

    static final String FILE_NAME = "TurboismMcpBridge.java";
    private static final String RESOURCE =
        "/META-INF/turboism/mcp/TurboismMcpBridge.java";
    private static final String STATE_DIR_PLACEHOLDER = "__TURBOISM_STATE_DIR__";

    private McpStdioBridge() {
    }

    /** Writes the bridge source unless an identical file is already present. */
    static Path publish(final Path stateDir) throws IOException {
        final Path file = Objects.requireNonNull(stateDir, "stateDir")
            .resolve(FILE_NAME);
        final byte[] content = sourceFor(stateDir).getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
            && Arrays.equals(Files.readAllBytes(file), content)) {
            return file;
        }
        McpStateFiles.publish(file, ".mcp-bridge-", content);
        return file;
    }

    static Map<String, Object> clientConfig(final Path stateDir) {
        return Map.of(
            "command", "java",
            "args", List.of(stateDir.resolve(FILE_NAME).toAbsolutePath().toString())
        );
    }

    /** Single-line client configuration snippet shown in the connection window. */
    static String clientConfigJson(final Path stateDir) {
        return new String(
            StrictJson.bytes(clientConfig(stateDir)),
            StandardCharsets.UTF_8
        );
    }

    private static String sourceFor(final Path stateDir) throws IOException {
        try (InputStream stream = McpStdioBridge.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                throw new IOException("MCP stdio bridge resource is missing");
            }
            final String source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            if (!source.contains(STATE_DIR_PLACEHOLDER)) {
                throw new IOException("MCP stdio bridge resource is malformed");
            }
            return source.replace(
                STATE_DIR_PLACEHOLDER,
                javaLiteral(stateDir.toAbsolutePath().toString())
            );
        }
    }

    private static String javaLiteral(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
