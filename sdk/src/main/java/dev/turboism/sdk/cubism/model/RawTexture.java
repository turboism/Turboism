package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.RawImageId;

/** Read-only projection of one raw layered image registered on the model. */
public interface RawTexture {

    RawImageId id();

    String name();

    int width();

    int height();
}
