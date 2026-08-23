package dev.turboism.sdk.hostread;

import java.util.Objects;

/**
 * Terminal failure detail attached to a rejected submission or a non-successful
 * {@link AsyncHostReadResult}.
 *
 * <p>The message is intended for diagnostics and logs, not for end-user display; it is bounded
 * to 256 characters so a misbehaving host cannot flood a report or a log sink.
 *
 * @param code machine-readable classification the caller is expected to branch on
 * @param message human-readable detail, never blank and never longer than 256 characters
 */
public record AsyncHostReadError(
    AsyncHostReadErrorCode code,
    String message
) {
    public AsyncHostReadError {
        code = Objects.requireNonNull(code, "code");
        message = Objects.requireNonNull(message, "message");
        if (message.isBlank() || message.length() > 256) {
            throw new IllegalArgumentException("message must be non-blank and at most 256 characters");
        }
    }
}
