package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.write.CubismWriteCommand;

/**
 * A request to perform one named mesh operation on a single art mesh, queued through the write
 * pipeline rather than executed here.
 *
 * <p>Describes intent only: constructing one does not touch the model, and the operation string is
 * interpreted by whichever executor consumes the command — this record does not validate it
 * against any known vocabulary, only that it is present.
 *
 * @param commandId caller-supplied identity for this request, used to correlate it with its
 *     result; must not be {@code null} or blank
 * @param modelId the model the mesh belongs to; must not be {@code null}
 * @param meshId the art mesh to operate on; must not be {@code null}
 * @param operation the operation name to perform, passed through verbatim to the executor; must
 *     not be {@code null} or blank
 */
public record MeshWriteCommand(
    String commandId,
    ModelId modelId,
    ModelObjectId meshId,
    String operation
) implements CubismWriteCommand {

    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException if any component is {@code null}, or if {@code commandId} or
     *     {@code operation} is blank
     */
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
