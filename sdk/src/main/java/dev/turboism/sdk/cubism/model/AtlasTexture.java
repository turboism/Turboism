package dev.turboism.sdk.cubism.model;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.cubism.id.TextureAtlasId;

/** Read-only projection of one texture atlas document. */
@PreviewApi
public interface AtlasTexture {

    TextureAtlasId id();

    String name();

    int width();

    int height();

    int atlasVersion();

    int modelImageCount();
}
