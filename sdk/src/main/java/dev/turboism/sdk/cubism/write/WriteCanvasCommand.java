package dev.turboism.sdk.cubism.write;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ModelId;

/**
 * Validated intent to resize a model's canvas. A DTO: it names the target by id and carries no
 * host object.
 *
 * @param commandId caller-chosen id echoed back on the matching {@link WriteResult}; non-blank
 * @param modelId model whose canvas is being resized
 * @param width new canvas width in pixels; at least one
 * @param height new canvas height in pixels; at least one
 * @throws IllegalArgumentException if {@code commandId} is null or blank, {@code modelId} is
 *     null, or either dimension is below one
 */
@PreviewApi
public record WriteCanvasCommand(String commandId, ModelId modelId, int width, int height) implements CubismWriteCommand {
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
