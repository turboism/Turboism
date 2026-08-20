package dev.turboism.adapter.cubism.command;

import dev.turboism.sdk.cubism.command.EditorFileCommand;
import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;

import java.nio.file.Path;
import java.util.Objects;

/** Runtime-only resolved file command; paths never cross the SDK boundary. */
public record ResolvedEditorFileCommand(
    EditorFileCommand command,
    Path file,
    EditorOverwritePolicy overwritePolicy
) {
    public ResolvedEditorFileCommand {
        command = Objects.requireNonNull(command, "command");
        file = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        overwritePolicy = Objects.requireNonNull(overwritePolicy, "overwritePolicy");
    }

    /**
     * @return the id of the underlying SDK command, so a result can be correlated back to the
     *     plugin request that produced it
     */
    public String commandId() {
        return command.id();
    }
}
