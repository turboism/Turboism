package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.cubism.id.TextureAtlasId;

/** Read-only projection of one texture atlas document. */
public interface AtlasTexture {

    /** Returns this atlas document's stable identity. */
    TextureAtlasId id();

    /** Returns the atlas display name. */
    String name();

    /** Returns the atlas texture width in pixels. */
    int width();

    /** Returns the atlas texture height in pixels. */
    int height();

    /** Returns the atlas file format version. */
    int atlasVersion();

    /** Returns the number of model images placed on this atlas. */
    int modelImageCount();
}
