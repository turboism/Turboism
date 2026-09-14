package dev.turboism.sdk.cubism.command;


import java.util.Set;

/** Executes the safe typed subset of native Cubism Editor menu operations. */
public interface EditorCommandService {
    /** Returns the commands the connected host can currently execute; empty when none apply. */
    Set<EditorCommand> available();

    /**
     * Executes one parameterless command.
     *
     * @param command the command to run
     * @return the sanitized result; {@link EditorCommandResult#executed()} reports whether the
     *     host actually applied it
     */
    EditorCommandResult execute(EditorCommand command);

    /**
     * Executes one file-carrying command described by {@code request}.
     *
     * @param request the typed file command request
     * @return the sanitized result; non-executed statuses mean nothing was applied
     */
    EditorCommandResult execute(EditorFileCommandRequest request);

    /**
     * Executes one parameterized command described by {@code request}.
     *
     * @param request the typed command request
     * @return the sanitized result; non-executed statuses mean nothing was applied
     */
    EditorCommandResult execute(EditorParameterizedRequest request);


    /** Returns the fail-closed service whose commands all report {@code UNAVAILABLE}. */
    static EditorCommandService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Singleton fail-closed implementation returned by {@link #unavailable()}. */
    enum Unavailable implements EditorCommandService {
        INSTANCE;

        @Override
        public Set<EditorCommand> available() {
            return Set.of();
        }

        @Override
        public EditorCommandResult execute(final EditorCommand command) {
            java.util.Objects.requireNonNull(command, "command");
            return new EditorCommandResult(EditorCommandResult.Status.UNAVAILABLE, command.id());
        }

        @Override
        public EditorCommandResult execute(final EditorFileCommandRequest request) {
            java.util.Objects.requireNonNull(request, "request");
            return new EditorCommandResult(EditorCommandResult.Status.UNAVAILABLE, request.commandId());
        }

        @Override
        public EditorCommandResult execute(final EditorParameterizedRequest request) {
            java.util.Objects.requireNonNull(request, "request");
            return new EditorCommandResult(EditorCommandResult.Status.UNAVAILABLE, request.commandId());
        }

    }
}
