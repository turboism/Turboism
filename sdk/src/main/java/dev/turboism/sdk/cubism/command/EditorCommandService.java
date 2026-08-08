package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.PreviewApi;

import java.util.Set;

/** Executes the safe typed subset of native Cubism Editor menu operations. */
@PreviewApi
public interface EditorCommandService {
    Set<EditorCommand> available();

    EditorCommandResult execute(EditorCommand command);

    EditorCommandResult execute(EditorFileCommandRequest request);

    EditorCommandResult execute(EditorParameterizedRequest request);


    static EditorCommandService unavailable() {
        return Unavailable.INSTANCE;
    }

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
