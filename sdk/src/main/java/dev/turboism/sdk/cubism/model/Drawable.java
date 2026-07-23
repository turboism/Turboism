package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.ArtMeshId;

/** One evaluated Cubism drawable/ArtMesh. */
@PreviewApi
public interface Drawable {

    ArtMeshId id();

    byte constantFlag();

    byte dynamicFlag();

    BlendMode blendMode();

    int textureIndex();

    int drawOrder();

    int renderOrder();

    float getOpacity();

    IntSequence masks();

    FloatSequence vertexPositions();

    FloatSequence vertexUvs();

    IntSequence indices();

    Color multiplyColor();

    Color screenColor();

    int parentPartIndex();

    int parentDeformerIndex();

    IntSequence parameters();
}
