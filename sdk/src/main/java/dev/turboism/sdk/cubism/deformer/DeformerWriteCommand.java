package dev.turboism.sdk.cubism.deformer;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.write.CubismWriteCommand;

public record DeformerWriteCommand(
    String commandId,
    ModelId modelId,
    ModelObjectId deformerId,
    String operation
) implements CubismWriteCommand {

    public DeformerWriteCommand {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be null or blank");
        }
        if (modelId == null) {
            throw new IllegalArgumentException("modelId must not be null");
        }
        if (deformerId == null) {
            throw new IllegalArgumentException("deformerId must not be null");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be null or blank");
        }
    }
}
