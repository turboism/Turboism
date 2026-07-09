package dev.turboism.sdk.cubism.psd;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.write.CubismWriteCommand;

public record PsdBindingWriteCommand(
    String commandId,
    ModelId modelId,
    String psdDocumentId,
    String layerId,
    ModelObjectId targetObjectId
) implements CubismWriteCommand {

    public PsdBindingWriteCommand {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be null or blank");
        }
        if (modelId == null) {
            throw new IllegalArgumentException("modelId must not be null");
        }
        if (psdDocumentId == null || psdDocumentId.isBlank()) {
            throw new IllegalArgumentException("psdDocumentId must not be null or blank");
        }
        if (layerId == null || layerId.isBlank()) {
            throw new IllegalArgumentException("layerId must not be null or blank");
        }
        if (targetObjectId == null) {
            throw new IllegalArgumentException("targetObjectId must not be null");
        }
    }
}
