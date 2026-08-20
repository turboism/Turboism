package dev.turboism.sdk.cubism.boundingbox;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.write.CubismWriteCommand;

@PreviewApi
/**
 * Validated intent to perform a bounding-box write on one model object. Like every
 * {@link CubismWriteCommand} it is a DTO: it names its target by id and exposes no host object,
 * hook, or reflection handle. All components are validated at construction, so an instance always
 * identifies a complete request.
 *
 * @param commandId identifier correlating this command with its transaction result; never blank
 * @param modelId the model owning the target object
 * @param objectId the model object whose bounding box is written
 * @param action the bounding-box action requested; never blank
 * @throws IllegalArgumentException when any component is null, or when {@code commandId} or
 *     {@code action} is blank
 */
public record BoundingBoxWriteCommand(
    String commandId,
    ModelId modelId,
    ModelObjectId objectId,
    String action
) implements CubismWriteCommand {

    public BoundingBoxWriteCommand {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be null or blank");
        }
        if (modelId == null) {
            throw new IllegalArgumentException("modelId must not be null");
        }
        if (objectId == null) {
            throw new IllegalArgumentException("objectId must not be null");
        }
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action must not be null or blank");
        }
    }
}
