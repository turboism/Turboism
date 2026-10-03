package dev.turboism.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.mcp.McpHttpConnection;
import dev.turboism.sdk.plugin.Registration;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

final class McpConnectionRegistryTest {

    @Test
    void staleRevocationCannotClearReplacementPublication() {
        final McpConnectionRegistry registry = new McpConnectionRegistry();
        final Registration first = registry.publish("mcp", connection(41001));
        final Registration replacement = registry.publish("mcp", connection(41002));

        first.close();

        assertEquals(41002, registry.current().orElseThrow().endpoint().getPort());
        replacement.close();
        assertTrue(registry.current().isEmpty());
    }

    @Test
    void rejectsPublicationAfterTerminalClose() {
        final McpConnectionRegistry registry = new McpConnectionRegistry();
        registry.publish("mcp", connection(41001));

        registry.close();

        assertTrue(registry.current().isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.publish("mcp", connection(41002)));
        assertTrue(registry.current().isEmpty());
    }

    @Test
    void rejectsPublicationFromAnotherPlugin() {
        final McpConnectionRegistry registry = new McpConnectionRegistry();
        registry.publish("mcp", connection(41001));

        assertThrows(IllegalStateException.class, () -> registry.publish("other", connection(41002)));
    }

    @Test
    void subscriberSeesInitialThenPublishRevokeInOrder() {
        final McpConnectionRegistry registry = new McpConnectionRegistry();
        final List<Optional<McpHttpConnection>> seen = new ArrayList<>();

        final Registration subscription = registry.subscribe(seen::add);
        assertEquals(List.of(Optional.empty()), seen);

        final Registration publication = registry.publish("mcp", connection(41001));
        assertEquals(41001, seen.get(1).orElseThrow().endpoint().getPort());

        publication.close();
        assertEquals(List.of(Optional.empty(), seen.get(1), Optional.empty()), seen);

        subscription.close();
        registry.publish("mcp", connection(41002));
        assertEquals(3, seen.size());
    }

    @Test
    void subscriberSeesReplacementAndRegistryClose() {
        final McpConnectionRegistry registry = new McpConnectionRegistry();
        registry.publish("mcp", connection(41001));
        final List<Optional<McpHttpConnection>> seen = new ArrayList<>();
        registry.subscribe(seen::add);

        registry.publish("mcp", connection(41002));
        registry.close();

        assertEquals(3, seen.size());
        assertEquals(41001, seen.get(0).orElseThrow().endpoint().getPort());
        assertEquals(41002, seen.get(1).orElseThrow().endpoint().getPort());
        assertTrue(seen.get(2).isEmpty());
    }

    @Test
    void subscribeAfterCloseReplaysEmptyOnly() {
        final McpConnectionRegistry registry = new McpConnectionRegistry();
        registry.close();
        final List<Optional<McpHttpConnection>> seen = new ArrayList<>();

        registry.subscribe(seen::add);

        assertEquals(List.of(Optional.empty()), seen);
    }

    @Test
    void failingListenerDoesNotCorruptRegistryOrPeers() {
        final McpConnectionRegistry registry = new McpConnectionRegistry();
        final List<Optional<McpHttpConnection>> seen = new ArrayList<>();
        registry.subscribe(snapshot -> {
            throw new IllegalStateException("boom");
        });
        registry.subscribe(seen::add);

        registry.publish("mcp", connection(41001));

        assertEquals(41001, registry.current().orElseThrow().endpoint().getPort());
        assertEquals(2, seen.size());
        assertTrue(seen.get(0).isEmpty());
        assertEquals(41001, seen.get(1).orElseThrow().endpoint().getPort());
    }

    private static McpHttpConnection connection(final int port) {
        return new McpHttpConnection(URI.create("http://127.0.0.1:" + port + "/mcp"), "2025-11-25");
    }
}
