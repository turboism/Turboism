package dev.turboism.sdk.cubism.textureatlas;


import java.util.Optional;

/** Reads and applies complete texture-atlas authoring layouts. */
public interface TextureAtlasLayoutService {

    Optional<TextureAtlasLayoutSnapshot> current();

    TextureAtlasLayoutApplyResult apply(
        TextureAtlasLayoutTarget target,
        TextureAtlasLayoutPlan plan
    );
}
