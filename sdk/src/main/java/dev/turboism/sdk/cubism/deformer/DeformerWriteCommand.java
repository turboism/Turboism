package dev.turboism.sdk.cubism.deformer;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.write.CubismWriteCommand;

/**
 * A write command targeting a single deformer within a model.
 *
 * <p>The compact constructor rejects incomplete commands eagerly, so any instance that
 * exists is fully addressed. The command only names the intended change; it neither
 * performs nor validates the edit against the live model.
 *
 * @param commandId caller-supplied identity for correlating this command with its outcome;
 *                  must not be null or blank
 * @param modelId the model the deformer belongs to; must not be null
 * @param deformerId the deformer to modify; must not be null
 * @param operation the deformer operation to perform, interpreted by the executing adapter;
 *                  must not be null or blank
 */
public record DeformerWriteCommand(
    String commandId,
    ModelId modelId,
    ModelObjectId deformerId,
    String operation
) implements CubismWriteCommand {

    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException if any component is null, or if {@code commandId} or
     *                                  {@code operation} is blank
     */
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
