package dev.turboism.plugin.mcp;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpSessionRegistryTest {

    @Test
    void expiresInactiveSessionsAndExtendsActiveOnes() {
        final MutableClock clock = new MutableClock(Instant.parse("2026-08-25T00:00:00Z"));
        final McpSessionRegistry registry = new McpSessionRegistry(
            Duration.ofSeconds(10), clock
        );
        final McpSessionRegistry.Session session = registry.create(McpProtocol.VERSION);

        clock.advance(Duration.ofSeconds(9));
        assertTrue(registry.find(session.id()).isPresent());
        clock.advance(Duration.ofSeconds(9));
        assertTrue(registry.find(session.id()).isPresent());
        clock.advance(Duration.ofSeconds(11));
        assertFalse(registry.find(session.id()).isPresent());
        assertFalse(registry.initialize(session.id()));
        assertFalse(registry.remove(session.id()));
    }

    @Test
    void reportsEachExpiredSessionOnce() {
        final MutableClock clock = new MutableClock(Instant.parse("2026-08-25T00:00:00Z"));
        final java.util.ArrayList<String> expired = new java.util.ArrayList<>();
        final McpSessionRegistry registry = new McpSessionRegistry(
            Duration.ofSeconds(10), clock, expired::add
        );
        final McpSessionRegistry.Session first = registry.create(McpProtocol.VERSION);
        final McpSessionRegistry.Session second = registry.create(McpProtocol.VERSION);

        clock.advance(Duration.ofSeconds(11));
        assertFalse(registry.find(first.id()).isPresent());
        assertEquals(java.util.List.of(first.id(), second.id()), expired);
        assertFalse(registry.find(second.id()).isPresent());
        assertEquals(java.util.List.of(first.id(), second.id()), expired);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(final Instant instant) {
            this.instant = instant;
        }

        void advance(final Duration duration) {
            instant = instant.plus(duration);
        }

        @Override public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override public Clock withZone(final ZoneId zone) {
            return this;
        }

        @Override public Instant instant() {
            return instant;
        }
    }
}
