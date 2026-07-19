package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.write.CubismWriteCommand;

@PreviewApi
public record MirrorWritebackCommand(
    String commandId,
    ModelId modelId,
    ModelObjectId sourceMeshId,
    ModelObjectId targetMeshId
) implements CubismWriteCommand {

    public MirrorWritebackCommand {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be null or blank");
        }
        if (modelId == null) {
            throw new IllegalArgumentException("modelId must not be null");
        }
        if (sourceMeshId == null) {
            throw new IllegalArgumentException("sourceMeshId must not be null");
        }
        if (targetMeshId == null) {
            throw new IllegalArgumentException("targetMeshId must not be null");
        }
    }
}
