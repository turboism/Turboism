package dev.turboism.sdk.cubism.write;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelObjectId;

import java.util.List;

public record WriteClipMaskCommand(String commandId, ModelObjectId clipMaskId, List<ArtMeshId> clippedMeshIds) implements CubismWriteCommand {
    public WriteClipMaskCommand {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be null or blank");
        }
        if (clipMaskId == null) {
            throw new IllegalArgumentException("clipMaskId must not be null");
        }
        clippedMeshIds = List.copyOf(clippedMeshIds);
    }
}
