package dev.turboism.plugin.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Shared-secret credential that gates mutating MCP operations on the loopback
 * endpoint. The token is generated once per installation, persisted owner-only
 * next to the connection file, and reused across restarts so configured clients
 * keep working. Read-only MCP methods never require it.
 */
final class McpAccessToken {

    static final String FILE_NAME = "mcp.token";

    private static final Pattern SHAPE = Pattern.compile("[0-9a-f]{64}");
    private static final String BEARER_PREFIX = "Bearer ";

    private final String value;
    private final Path file;

    private McpAccessToken(final String value, final Path file) {
        this.value = value;
        this.file = file;
    }

    /**
     * Reuses the persisted token when it is a well-formed, trusted file and
     * publishes a fresh 256-bit token otherwise. Unsafe existing files (symlinks,
     * foreign owners) are rejected rather than overwritten.
     */
    static McpAccessToken loadOrCreate(final Path stateDir) throws IOException {
        final Path file = Objects.requireNonNull(stateDir, "stateDir").resolve(FILE_NAME);
        final String existing = readTrusted(file);
        if (existing != null) return new McpAccessToken(existing, file);
        final byte[] generated = new byte[32];
        new SecureRandom().nextBytes(generated);
        final StringBuilder hex = new StringBuilder(64);
        for (byte value : generated) {
            hex.append(Character.forDigit((value >> 4) & 0xF, 16));
            hex.append(Character.forDigit(value & 0xF, 16));
        }
        final String token = hex.toString();
        McpStateFiles.publish(file, ".mcp-token-", (token + "\n").getBytes(StandardCharsets.UTF_8));
        return new McpAccessToken(token, file);
    }

    private static String readTrusted(final Path file) throws IOException {
        if (!Files.exists(file)) return null;
        McpStateFiles.requireTrustedFile(file);
        final String content = Files.readString(file, StandardCharsets.UTF_8).strip();
        return SHAPE.matcher(content).matches() ? content : null;
    }

    /** Constant-time check that a request carries {@code Authorization: Bearer <token>}. */
    boolean accepts(final String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length())) {
            return false;
        }
        final String presented =
                authorizationHeader.substring(BEARER_PREFIX.length()).strip();
        return MessageDigest.isEqual(
                value.getBytes(StandardCharsets.UTF_8), presented.getBytes(StandardCharsets.UTF_8));
    }

    Path file() {
        return file;
    }
}
