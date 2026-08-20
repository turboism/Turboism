package dev.turboism.core.runtime;

/**
 * Thread-local context that holds the current cancellation token for the
 * executing plugin work item.
 *
 * <p>Runtime internal: the runtime binds a token before invoking a plugin
 * work item and clears it afterward. This class is part of the runtime
 * implementation and must not be exposed to SDK or plugin code.
 */
public final class CancellationContext {

    private static final ThreadLocal<RuntimeCancellationToken> CURRENT = new ThreadLocal<>();

    private CancellationContext() {
        // Utility class.
    }

    /**
     * Binds a token to the calling thread. The runtime calls this immediately before invoking a
     * plugin work item and must pair it with {@link #clear()} in a {@code finally} block,
     * otherwise the token leaks onto a pooled thread and a later, unrelated work item observes a
     * stale cancellation state.
     *
     * @param token the token for the work item about to run on this thread
     */
    public static void set(final RuntimeCancellationToken token) {
        CURRENT.set(token);
    }

    /**
     * @return the token bound to the calling thread, or {@code null} when this thread is not
     *     currently executing a plugin work item
     */
    public static RuntimeCancellationToken get() {
        return CURRENT.get();
    }

    /**
     * Unbinds the calling thread's token. Safe to call when nothing is bound.
     */
    public static void clear() {
        CURRENT.remove();
    }
}
