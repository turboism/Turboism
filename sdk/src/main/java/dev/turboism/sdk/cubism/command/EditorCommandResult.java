package dev.turboism.sdk.cubism.command;


import java.util.Objects;

/** Sanitized result of one semantic Editor command invocation. */
public record EditorCommandResult(Status status, String commandId) {
    public EditorCommandResult {
        status = Objects.requireNonNull(status, "status");
        commandId = requireText(commandId, "commandId");
    }

    /**
     * @return whether the host performed the command; a performed command may still be a
     *     legitimate no-op that changes nothing (see {@link Status#EXECUTED}), while every
     *     other status means the command was not carried out
     */
    public boolean executed() {
        return status == Status.EXECUTED;
    }

    /**
     * Outcome of one command invocation. {@code EXECUTED} means the host carried the
     * command out, which can include a recognized no-op; it does not by itself imply a
     * document change, dirty state or an Undo entry.
     */
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
