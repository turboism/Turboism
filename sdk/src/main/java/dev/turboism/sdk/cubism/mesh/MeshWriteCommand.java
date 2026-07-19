package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.write.CubismWriteCommand;

@PreviewApi
public record MeshWriteCommand(
    String commandId,
    ModelId modelId,
    ModelObjectId meshId,
    String operation
) implements CubismWriteCommand {

    public MeshWriteCommand {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be null or blank");
        }
        if (modelId == null) {
            throw new IllegalArgumentException("modelId must not be null");
        }
        if (meshId == null) {
            throw new IllegalArgumentException("meshId must not be null");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be null or blank");
        }
    }
}
