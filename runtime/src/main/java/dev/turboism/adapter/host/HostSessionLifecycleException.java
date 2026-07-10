package dev.turboism.adapter.host;

/** Sanitized unchecked failure reported when explicit session shutdown cannot finish cleanup. */
public final class HostSessionLifecycleException extends IllegalStateException {

    HostSessionLifecycleException(final String message) {
        super(message);
    }
}
