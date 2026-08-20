package dev.turboism.sdk.plugin;

/**
 * Thrown by {@link CancellationToken#checkCanceled()} to unwind a plugin task
 * whose cancellation was requested.
 *
 * <p>Unchecked so that it can escape task bodies without changing their
 * signatures; it signals an orderly abort, not a fault.</p>
 */
public final class TaskCanceledException extends RuntimeException {

    public TaskCanceledException() {
        super();
    }

    public TaskCanceledException(final String message) {
        super(message);
    }

    public TaskCanceledException(final String message, final Throwable cause) {
        super(message, cause);
    }

    public TaskCanceledException(final Throwable cause) {
        super(cause);
    }
}
