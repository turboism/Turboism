package dev.turboism.sdk.cubism.write;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelObjectId;

import java.util.List;

/**
 * Validated intent to set the full clipped-mesh list of one clipping mask, replacing whatever it
 * currently clips.
 *
 * <p>{@code clippedMeshIds} is defensively copied into an immutable list, so a caller mutating
 * the list it passed in cannot alter the command.
 *
 * @param commandId caller-chosen id echoed back on the matching {@link WriteResult}; non-blank
 * @param clipMaskId the object acting as the clipping mask
 * @param clippedMeshIds art meshes the mask should clip after the write; an empty list clears the
 *     mask's targets
 */
@PreviewApi
public record WriteClipMaskCommand(String commandId, ModelObjectId clipMaskId, List<ArtMeshId> clippedMeshIds) implements CubismWriteCommand {
    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException if {@code commandId} is null or blank, or {@code clipMaskId}
     *     is null
     * @throws NullPointerException if {@code clippedMeshIds} is null or contains a null element
     */
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
