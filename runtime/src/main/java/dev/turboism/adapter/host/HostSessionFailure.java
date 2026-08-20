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

    public enum Code {
        SOURCE_FAILED,
        CONNECTION_FAILED,
        CLEANUP_FAILED
    }
}
