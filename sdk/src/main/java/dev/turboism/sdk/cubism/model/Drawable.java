package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ArtMeshId;

/** One evaluated Cubism drawable/ArtMesh. */
@PreviewApi
public interface Drawable {

    ArtMeshId id();

    float getOpacity();

    FloatSequence vertexPositions();

    FloatSequence vertexUvs();

    IntSequence indices();
}
