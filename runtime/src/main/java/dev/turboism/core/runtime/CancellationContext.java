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

    public static void set(final RuntimeCancellationToken token) {
        CURRENT.set(token);
    }

    public static RuntimeCancellationToken get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }
}
