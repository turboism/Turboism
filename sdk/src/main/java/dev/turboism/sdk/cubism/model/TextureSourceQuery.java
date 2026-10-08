package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import java.util.Set;

/** Scoped source lookup: selected ArtMeshes and/or stable model-image anchors, never a full graph. */
public record TextureSourceQuery(Set<ArtMeshId> artMeshes, Set<ModelImageId> modelImages) {
    public TextureSourceQuery {
        artMeshes = Set.copyOf(artMeshes);
        modelImages = Set.copyOf(modelImages);
    }
}
