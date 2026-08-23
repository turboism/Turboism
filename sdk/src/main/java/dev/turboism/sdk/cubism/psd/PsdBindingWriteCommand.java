package dev.turboism.sdk.cubism.psd;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ModelObjectId;
import dev.turboism.sdk.cubism.write.CubismWriteCommand;

@PreviewApi
/**
 * Preview-API write command binding one PSD layer to one Cubism model object.
 *
 * <p>Constructing the command only describes the intent; it performs no host mutation. Every
 * component is validated eagerly and a null or blank value is rejected with
 * {@link IllegalArgumentException}.</p>
 *
 * @param commandId caller-assigned identity for this command, never blank
 * @param modelId model whose binding is being written
 * @param psdDocumentId PSD document the layer belongs to, never blank
 * @param layerId layer inside that PSD document, never blank
 * @param targetObjectId model object the layer is bound to
 */
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
