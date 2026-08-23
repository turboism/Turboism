package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.ui.UserFileHandle;
import dev.turboism.sdk.ui.UserFileHandleState;

import java.util.Objects;

/** Validated file-backed Editor command without exposing a filesystem path. */
@PreviewApi
public record EditorFileCommandRequest(
    EditorFileCommand command,
    UserFileHandle file,
    EditorOverwritePolicy overwritePolicy
) {
    public EditorFileCommandRequest {
        command = Objects.requireNonNull(command, "command");
        file = Objects.requireNonNull(file, "file");
        overwritePolicy = Objects.requireNonNull(overwritePolicy, "overwritePolicy");
        if (file.state() != UserFileHandleState.ACTIVE) {
            throw new IllegalArgumentException("file grant must be active");
        }
        if (file.mode() != command.mode()) {
            throw new IllegalArgumentException("file grant mode does not match command");
        }
    }

    /** @return the host command identifier of the requested file command, for logging and dispatch */
    public String commandId() {
        return command.id();
    }
}
