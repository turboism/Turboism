package dev.turboism.plugin.mcp;

import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.mcp.McpStdioLaunch;
import dev.turboism.sdk.plugin.PluginLogger;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Publishes the stdio↔HTTP bridge source next to the access token in the plugin
 * state directory and computes the credential-free launch descriptor offered to
 * trusted consumers through the runtime connection boundary. External MCP clients
 * spawn {@code java TurboismMcpBridge.java} on the published source; ACP agents
 * instead launch the compiled bridge class inside this plugin JAR with the state
 * directory as an argument. Either way the bridge forwards each stdin JSON-RPC
 * line to the loopback endpoint carrying the persisted bearer token, so users
 * never handle credentials themselves.
 */
final class McpStdioBridge {

    static final String FILE_NAME = "TurboismMcpBridge.java";
    static final String MAIN_CLASS = "dev.turboism.plugin.mcp.TurboismMcpBridge";
    private static final String RESOURCE = "/META-INF/turboism/mcp/TurboismMcpBridge.java";
    private static final String STATE_DIR_PLACEHOLDER = "__TURBOISM_STATE_DIR__";

    private McpStdioBridge() {}

    /**
     * Writes the bridge source unless an identical file is already present.
     * {@code warning} is invoked when the filesystem cannot express owner-only
     * permissions for the published file.
     */
    static Path publish(final Path stateDir) throws IOException {
        return publish(stateDir, warning -> {});
    }

    static Path publish(final Path stateDir, final java.util.function.Consumer<String> warning) throws IOException {
        final Path file = Objects.requireNonNull(stateDir, "stateDir").resolve(FILE_NAME);
        final byte[] content = sourceFor(stateDir).getBytes(StandardCharsets.UTF_8);
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Arrays.equals(Files.readAllBytes(file), content)) {
            return file;
        }
        McpStateFiles.publish(file, ".mcp-bridge-", content, warning);
        return file;
    }

    static Map<String, Object> clientConfig(final Path stateDir) {
        return Map.of(
                "command",
                "java",
                "args",
                List.of(stateDir.resolve(FILE_NAME).toAbsolutePath().toString()));
    }

    /** Single-line client configuration snippet shown in the connection window. */
    static String clientConfigJson(final Path stateDir) {
        return Json.stringify(clientConfig(stateDir));
    }

    /**
     * Computes the stdio launch descriptor for the compiled bridge inside this
     * plugin JAR (or its build classes directory in development). The command is
     * the running JVM's own launcher, so the descriptor does not depend on PATH
     * or on a {@code jdk.compiler} module being present. It fails closed to
     * empty — never a guessed path — when the launcher or the plugin code source
     * is not a regular file or directory, and logs a single warning that carries
     * no paths or token material.
     */
    static Optional<McpStdioLaunch> launch(final Path stateDir, final PluginLogger logger) {
        return launch(stateDir, logger, javaLauncher(), codeSource());
    }

    /** Launch seam that lets tests exercise the fail-closed branches deterministically. */
    static Optional<McpStdioLaunch> launch(
            final Path stateDir, final PluginLogger logger, final Path launcher, final Path codeSource) {
        Objects.requireNonNull(logger, "logger");
        final Optional<McpStdioLaunch> descriptor =
                launchDescriptor(launcher, codeSource, Objects.requireNonNull(stateDir, "stateDir"));
        if (descriptor.isEmpty()) {
            logger.warn("MCP stdio launch unavailable");
        }
        return descriptor;
    }

    /**
     * Combines the resolved launcher and code source into a validated descriptor, failing closed
     * unless the launcher is a regular file and the code source is a regular file or directory.
     */
    static Optional<McpStdioLaunch> launchDescriptor(final Path launcher, final Path codeSource, final Path stateDir) {
        if (launcher == null || codeSource == null) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (!Files.isRegularFile(codeSource, LinkOption.NOFOLLOW_LINKS)
                && !Files.isDirectory(codeSource, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(new McpStdioLaunch(
                launcher.toAbsolutePath().normalize().toString(),
                List.of(
                        "-cp",
                        codeSource.toAbsolutePath().normalize().toString(),
                        MAIN_CLASS,
                        stateDir.toAbsolutePath().normalize().toString())));
    }

    private static Path javaLauncher() {
        final String javaHome = System.getProperty("java.home");
        if (javaHome == null || javaHome.isBlank()) {
            return null;
        }
        final String executable =
                System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        return Path.of(javaHome).resolve("bin").resolve(executable);
    }

    private static Path codeSource() {
        try {
            final URL location = TurboismMcpBridge.class
                    .getProtectionDomain()
                    .getCodeSource()
                    .getLocation();
            if (location == null || !"file".equals(location.getProtocol())) {
                return null;
            }
            return Path.of(location.toURI());
        } catch (URISyntaxException | RuntimeException failure) {
            return null;
        }
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
            // The absolute path lands inside a Java string literal in the published
            // bridge source, so every character that could escape the literal or
            // confuse the single-file launcher is mapped to a standard escape.
            return source.replace(
                    STATE_DIR_PLACEHOLDER, javaLiteral(stateDir.toAbsolutePath().toString()));
        }
    }

    private static String javaLiteral(final String value) {
        final StringBuilder out = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            final char c = value.charAt(index);
            switch (c) {
                case '\\':
                    out.append("\\\\");
                    break;
                case '"':
                    out.append("\\\"");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20 || c == 0x7F || !Character.isDefined(c)) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
