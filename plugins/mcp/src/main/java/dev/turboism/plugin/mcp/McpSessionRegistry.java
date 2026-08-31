package dev.turboism.plugin.mcp;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** In-memory Streamable HTTP session lifecycle, bound to the server bearer context. */
final class McpSessionRegistry {

    private static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(30);

    private final SecureRandom random = new SecureRandom();
    private final Map<String, StoredSession> sessions = new LinkedHashMap<>();
    private final Duration idleTimeout;
    private final Clock clock;
    private final java.util.function.Consumer<String> onExpired;

    McpSessionRegistry() {
        this(DEFAULT_IDLE_TIMEOUT, Clock.systemUTC(), ignored -> { });
    }

    McpSessionRegistry(final Duration idleTimeout, final Clock clock) {
        this(idleTimeout, clock, ignored -> { });
    }

    McpSessionRegistry(
        final Duration idleTimeout,
        final Clock clock,
        final java.util.function.Consumer<String> onExpired
    ) {
        this.idleTimeout = java.util.Objects.requireNonNull(idleTimeout, "idleTimeout");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
        this.onExpired = java.util.Objects.requireNonNull(onExpired, "onExpired");
        if (idleTimeout.isNegative() || idleTimeout.isZero()) {
            throw new IllegalArgumentException("idleTimeout must be positive");
        }
    }

    synchronized Session create(final String protocolVersion) {
        pruneExpired();
        final byte[] bytes = new byte[24];
        String id;
        do {
            random.nextBytes(bytes);
            id = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (sessions.containsKey(id));
        final Session session = new Session(id, protocolVersion, false);
        sessions.put(id, new StoredSession(session, clock.instant()));
        return session;
    }

    synchronized Optional<Session> find(final String id) {
        pruneExpired();
        final StoredSession current = sessions.get(id);
        if (current == null) return Optional.empty();
        sessions.put(id, new StoredSession(current.session(), clock.instant()));
        return Optional.of(current.session());
    }

    synchronized boolean initialize(final String id) {
        pruneExpired();
        final StoredSession current = sessions.get(id);
        if (current == null) return false;
        sessions.put(id, new StoredSession(
            new Session(id, current.session().protocolVersion(), true),
            clock.instant()
        ));
        return true;
    }

    synchronized boolean remove(final String id) {
        pruneExpired();
        return sessions.remove(id) != null;
    }

    private void pruneExpired() {
        final Instant cutoff = clock.instant().minus(idleTimeout);
        final java.util.ArrayList<String> expired = new java.util.ArrayList<>();
        sessions.entrySet().removeIf(entry -> {
            final boolean remove = entry.getValue().lastAccess().isBefore(cutoff);
            if (remove) expired.add(entry.getKey());
            return remove;
        });
        expired.forEach(onExpired);
    }

    record Session(String id, String protocolVersion, boolean initialized) {
    }

    private record StoredSession(Session session, Instant lastAccess) {
    }
}
