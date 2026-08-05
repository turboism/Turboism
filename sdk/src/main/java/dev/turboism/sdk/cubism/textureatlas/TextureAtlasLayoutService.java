package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.PreviewApi;

import java.util.Optional;

/** Reads and applies complete texture-atlas authoring layouts. */
@PreviewApi
public interface TextureAtlasLayoutService {

    Optional<TextureAtlasLayoutSnapshot> current();

    TextureAtlasLayoutApplyResult apply(
        TextureAtlasLayoutTarget target,
        TextureAtlasLayoutPlan plan
    );
}
