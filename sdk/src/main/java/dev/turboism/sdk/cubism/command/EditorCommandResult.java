package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;

/** Sanitized result of one semantic Editor command invocation. */
@PreviewApi
public record EditorCommandResult(Status status, String commandId) {
    public EditorCommandResult {
        status = Objects.requireNonNull(status, "status");
        commandId = requireText(commandId, "commandId");
    }

    /**
     * @return whether the host actually performed the command; every other status means nothing was
     *     applied to the document, so callers must not treat a non-executed result as a partial
     *     success
     */
    public boolean executed() {
        return status == Status.EXECUTED;
    }

    @PreviewApi
    public enum Status {
        EXECUTED,
        UNAVAILABLE,
        INVALID_STATE,
        UNSUPPORTED_VERSION,
        PERMISSION_DENIED,
        REJECTED,
        FAILED
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
