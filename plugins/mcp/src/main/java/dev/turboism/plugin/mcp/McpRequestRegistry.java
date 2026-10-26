package dev.turboism.plugin.mcp;

import dev.turboism.sdk.plugin.Registration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Best-effort cancellation registry for MCP requests that have not committed to the host. */
final class McpRequestRegistry {

    private static final ThreadLocal<Token> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<String> FORWARDED_SESSION = new ThreadLocal<>();

    private final Map<Key, Token> active = new LinkedHashMap<>();

    synchronized Scope enter(final String sessionId, final Object requestId) {
        final Key key = new Key(sessionId, requestId);
        if (active.containsKey(key)) {
            throw new IllegalArgumentException("MCP request is already active: " + requestId);
        }
        final Token token = new Token(sessionId);
        active.put(key, token);
        CURRENT.set(token);
        return () -> {
            CURRENT.remove();
            synchronized (McpRequestRegistry.this) {
                active.remove(key, token);
            }
        };
    }

    synchronized boolean cancel(final String sessionId, final Object requestId) {
        final Token token = active.get(new Key(sessionId, requestId));
        return token != null && token.cancel();
    }

    static Cancellation currentCancellation() {
        final Token token = CURRENT.get();
        return token == null ? () -> false : token::cancelled;
    }

    /** Preserve session ownership across the UI queue without forwarding mid-write cancellation. */
    static String currentSessionId() {
        final Token token = CURRENT.get();
        return token != null ? token.sessionId : Objects.requireNonNullElse(FORWARDED_SESSION.get(), "");
    }

    static <T> T inSession(final String sessionId, final java.util.function.Supplier<T> work) {
        final String previous = FORWARDED_SESSION.get();
        FORWARDED_SESSION.set(Objects.requireNonNull(sessionId, "sessionId"));
        try {
            return work.get();
        } finally {
            if (previous == null) FORWARDED_SESSION.remove();
            else FORWARDED_SESSION.set(previous);
        }
    }

    static boolean cancelled() {
        return currentCancellation().cancelled();
    }

    static Registration onCancellation(final Runnable action) {
        final Token token = CURRENT.get();
        if (token == null) return () -> {};
        return token.onCancellation(action);
    }

    static void throwIfCancelled() {
        if (cancelled()) throw new CancellationException("MCP request was cancelled");
    }

    @FunctionalInterface
    interface Cancellation {
        boolean cancelled();
    }

    @FunctionalInterface
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    private record Key(String sessionId, Object requestId) {
        private Key {
            sessionId = Objects.requireNonNull(sessionId, "sessionId");
            requestId = Objects.requireNonNull(requestId, "requestId");
        }
    }

    private static final class Token {
        private final String sessionId;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

        private Token(final String sessionId) {
            this.sessionId = sessionId;
        }

        private boolean cancel() {
            if (!cancelled.compareAndSet(false, true)) return false;
            for (Runnable listener : listeners) listener.run();
            listeners.clear();
            return true;
        }

        private Registration onCancellation(final Runnable action) {
            final Runnable checked = Objects.requireNonNull(action, "action");
            if (cancelled()) {
                checked.run();
                return () -> {};
            }
            listeners.add(checked);
            if (cancelled() && listeners.remove(checked)) checked.run();
            return () -> listeners.remove(checked);
        }

        private boolean cancelled() {
            return cancelled.get();
        }
    }
}
