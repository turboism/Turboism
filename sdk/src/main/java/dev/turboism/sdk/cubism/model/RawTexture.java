package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.RawImageId;

/** Read-only projection of one raw layered image registered on the model. */
public interface RawTexture {

    /** Returns this raw image's stable identity. */
    RawImageId id();

    /** Returns the image's display name. */
    String name();

    /** Returns the image width in pixels. */
    int width();

    /** Returns the image height in pixels. */
    int height();
}
