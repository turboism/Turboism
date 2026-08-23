package dev.turboism.sdk.cubism.write;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelObjectId;

/**
 * Validated intent to apply a named operation to a single model object.
 *
 * <p>The operation is carried as text and is not interpreted here; the executor decides whether
 * it recognises the name and rejects the command otherwise.
 *
 * @param commandId caller-chosen id echoed back on the matching {@link WriteResult}; non-blank
 * @param modelId model owning the target object
 * @param objectId the object to operate on
 * @param operation non-blank operation name, validated for shape only
 */
public record WriteModelObjectCommand(String commandId, ModelId modelId, ModelObjectId objectId, String operation) implements CubismWriteCommand {
    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException if {@code commandId} or {@code operation} is null or blank, or
     *     {@code modelId} or {@code objectId} is null
     */
    public WriteModelObjectCommand {
        if (commandId == null || commandId.isBlank()) {
            throw new IllegalArgumentException("commandId must not be null or blank");
        }
        if (modelId == null) {
            throw new IllegalArgumentException("modelId must not be null");
        }
        if (objectId == null) {
            throw new IllegalArgumentException("objectId must not be null");
        }
        if (operation == null || operation.isBlank()) {
            throw new IllegalArgumentException("operation must not be null or blank");
        }
    }
}
