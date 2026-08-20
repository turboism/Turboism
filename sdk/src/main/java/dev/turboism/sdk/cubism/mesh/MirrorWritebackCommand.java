package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.write.CubismWriteCommand;

/**
 * A request to mirror one art mesh's geometry back onto another, queued through the write pipeline
 * rather than executed here.
 *
 * <p>Describes intent only: constructing one does not touch the model. The two mesh ids are not
 * checked for distinctness or for membership in {@code modelId} — that is the executor's concern.
 *
 * @param commandId caller-supplied identity for this request, used to correlate it with its
 *     result; must not be {@code null} or blank
 * @param modelId the model both meshes belong to; must not be {@code null}
 * @param sourceMeshId the mesh whose geometry is read; must not be {@code null}
 * @param targetMeshId the mesh the mirrored geometry is written to; must not be {@code null}
 */
@PreviewApi
public record MirrorWritebackCommand(
    String commandId,
    ModelId modelId,
    ModelObjectId sourceMeshId,
    ModelObjectId targetMeshId
) implements CubismWriteCommand {

    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException if any component is {@code null}, or if {@code commandId} is
     *     blank
     */
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
