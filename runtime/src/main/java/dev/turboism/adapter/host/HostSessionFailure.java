package dev.turboism.adapter.host;

import java.util.Objects;

/**
 * Immutable record of why a host session left its healthy path, retained as the session's last
 * failure and surfaced to diagnostics.
 *
 * @param code which stage failed: producing the host instance, connecting adapters, or cleaning up
 * @param message runtime-authored explanation; must not be blank
 */
public record HostSessionFailure(Code code, String message) {
    public HostSessionFailure {
        code = Objects.requireNonNull(code, "code");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    /** Which stage of the session left its healthy path. */
    public enum Code {
        /** Producing the host instance descriptor failed. */
        SOURCE_FAILED,
        /** Connecting the adapter set to the host failed. */
        CONNECTION_FAILED,
        /** Session cleanup after a failure itself failed. */
        CLEANUP_FAILED
    }
}
