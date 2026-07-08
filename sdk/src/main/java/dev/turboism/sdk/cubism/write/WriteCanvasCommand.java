package dev.turboism.sdk.cubism.write;

import dev.turboism.sdk.cubism.id.ModelId;

public record WriteCanvasCommand(String commandId, ModelId modelId, int width, int height) {
    public WriteCanvasCommand {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be null or blank");
        }
        if (modelId == null) {
            throw new IllegalArgumentException("modelId must not be null");
        }
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("canvas dimensions must be positive");
        }
    }
}
