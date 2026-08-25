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

    McpSessionRegistry() {
        this(DEFAULT_IDLE_TIMEOUT, Clock.systemUTC());
    }

    McpSessionRegistry(final Duration idleTimeout, final Clock clock) {
        this.idleTimeout = java.util.Objects.requireNonNull(idleTimeout, "idleTimeout");
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
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
        sessions.entrySet().removeIf(entry -> entry.getValue().lastAccess().isBefore(cutoff));
    }

    record Session(String id, String protocolVersion, boolean initialized) {
    }

    private record StoredSession(Session session, Instant lastAccess) {
    }
}
