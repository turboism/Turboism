package dev.turboism.sdk.cubism;

import java.util.Objects;
import java.util.Optional;

/**
 * Immutable snapshot of a single art mesh belonging to the model being edited.
 *
 * @param id stable Editor-assigned identifier of the art mesh; never blank-checked but never null
 * @param name display name shown in the Editor's part/object tree
 * @param textureId identifier of the texture this mesh samples, empty when no texture is bound
 * @param visible whether the mesh is marked visible in the Editor
 * @param renderable whether the mesh currently participates in rendering; a mesh may be visible
 *     yet not renderable (for example when its owning part is hidden)
 */
public record ArtMeshSnapshot(
    String id,
    String name,
    Optional<String> textureId,
    boolean visible,
    boolean renderable
) implements ModelObjectSnapshot {
    public ArtMeshSnapshot {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name");
        textureId = Objects.requireNonNull(textureId, "textureId");
    }
}
