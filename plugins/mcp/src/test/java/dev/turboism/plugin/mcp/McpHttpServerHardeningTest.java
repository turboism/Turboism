package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers the loopback Host whitelist and the owner-only publish warning channel. */
final class McpHttpServerHardeningTest {

    @Test
    void hostHeaderWhitelistAcceptsLoopbackAndRejectsForeign() {
        assertTrue(McpHttpServer.hostAllowed("127.0.0.1"));
        assertTrue(McpHttpServer.hostAllowed("127.0.0.1:43123"));
        assertTrue(McpHttpServer.hostAllowed("localhost"));
        assertTrue(McpHttpServer.hostAllowed("LOCALHOST:43123"));
        assertTrue(McpHttpServer.hostAllowed("[::1]"));
        assertTrue(McpHttpServer.hostAllowed("[::1]:43123"));
        assertFalse(McpHttpServer.hostAllowed(null));
        assertFalse(McpHttpServer.hostAllowed(""));
        assertFalse(McpHttpServer.hostAllowed("evil.example.com"));
        assertFalse(McpHttpServer.hostAllowed("127.0.0.1.evil.com"));
        assertFalse(McpHttpServer.hostAllowed("localhost:abc"));
        assertFalse(McpHttpServer.hostAllowed("[::2]"));
        assertFalse(McpHttpServer.hostAllowed("::1"));
    }

    @Test
    void publishRoundTripsContentAndStaysSilentOnPosix(@TempDir final Path stateDir) throws Exception {
        Assumptions.assumeTrue(
                Files.getFileStore(stateDir).supportsFileAttributeView("posix"),
                "owner-only assertion only meaningful on POSIX filesystems");
        final Path target = stateDir.resolve("mcp-connection.json");
        final List<String> warnings = new ArrayList<>();
        McpStateFiles.publish(target, ".mcp-test-", "payload".getBytes(StandardCharsets.UTF_8), warnings::add);
        assertEquals("payload", Files.readString(target, StandardCharsets.UTF_8));
        assertTrue(warnings.isEmpty(), () -> String.join(" | ", warnings));
        final var permissions = Files.getPosixFilePermissions(target);
        assertTrue(permissions.stream().allMatch(p -> p.name().startsWith("OWNER_")), () -> permissions.toString());
    }
}
