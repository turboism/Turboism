package dev.turboism.adapter.cubism.command;

import dev.turboism.sdk.cubism.command.EditorCommand;
import dev.turboism.sdk.cubism.command.EditorCommandResult;
import dev.turboism.sdk.cubism.command.EditorParameterizedRequest;

import java.util.Set;

/** Versioned host seam for safe no-argument Editor commands. */
public interface EditorCommandAdapter {
    Set<EditorCommand> available();

    EditorCommandResult execute(EditorCommand command);

    EditorCommandResult execute(ResolvedEditorFileCommand command);

    EditorCommandResult execute(EditorParameterizedRequest command);

    static EditorCommandAdapter unavailable() {
        return Unavailable.INSTANCE;
    }

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
