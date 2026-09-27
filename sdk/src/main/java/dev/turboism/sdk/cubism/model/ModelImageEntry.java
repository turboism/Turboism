package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.ModelImageId;

/** One model image (texture slot) inside a model image group. */
public interface ModelImageEntry {

    /** Returns this image entry's stable identity. */
    ModelImageId id();

    /** Returns the image's display name. */
    String name();

    /** Returns the image width in pixels. */
    int width();

    /** Returns the image height in pixels. */
    int height();
}
