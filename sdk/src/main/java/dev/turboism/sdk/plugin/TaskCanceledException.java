package dev.turboism.sdk.plugin;

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
