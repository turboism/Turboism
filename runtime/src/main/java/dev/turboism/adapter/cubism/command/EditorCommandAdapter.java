package dev.turboism.adapter.cubism.command;

import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorParameterizedRequest;

import java.util.Set;

/** Versioned host seam for safe no-argument Editor commands. */
public interface EditorCommandAdapter {
    /**
     * @return the commands this host version admits; empty when none is verified
     */
    Set<EditorCommand> available();

    /**
     * Executes a safe no-argument Editor command.
     *
     * @param command the command to run
     * @return the structured result; a status other than success instead of throwing for
     *         ordinary host rejections
     */
    EditorCommandResult execute(EditorCommand command);

    /**
     * Executes a file command already resolved through {@link EditorFileCommandResolver}.
     *
     * @param command the resolved file command
     * @return the structured result
     */
    EditorCommandResult execute(ResolvedEditorFileCommand command);

    /**
     * Executes a parameterized Editor command request.
     *
     * @param command the parameterized request
     * @return the structured result
     */
    EditorCommandResult execute(EditorParameterizedRequest command);

    /**
     * An adapter for when no host command surface is attached.
     *
     * @return the fail-closed adapter reporting no available commands and answering every
     *         execute with {@link EditorCommandResult.Status#UNAVAILABLE}
     */
    static EditorCommandAdapter unavailable() {
        return Unavailable.INSTANCE;
    }

    /**
     * Fail-closed adapter used when no host is attached: {@link #available()} is empty and
     * every {@code execute} returns an {@link EditorCommandResult.Status#UNAVAILABLE}
     * result for the request's command id.
     */
    enum Unavailable implements EditorCommandAdapter {
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
        public EditorCommandResult execute(final ResolvedEditorFileCommand command) {
            java.util.Objects.requireNonNull(command, "command");
            return new EditorCommandResult(EditorCommandResult.Status.UNAVAILABLE, command.commandId());
        }

        @Override
        public EditorCommandResult execute(final EditorParameterizedRequest command) {
            java.util.Objects.requireNonNull(command, "command");
            return new EditorCommandResult(EditorCommandResult.Status.UNAVAILABLE, command.commandId());
        }
    }
}
