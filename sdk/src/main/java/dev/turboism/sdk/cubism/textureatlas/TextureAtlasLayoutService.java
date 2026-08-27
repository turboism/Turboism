package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;

import java.util.Optional;

/** Reads and applies complete texture-atlas authoring layouts. */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface TextureAtlasLayoutService {

    Optional<TextureAtlasLayoutSnapshot> current();

    TextureAtlasLayoutApplyResult apply(
        TextureAtlasLayoutTarget target,
        TextureAtlasLayoutPlan plan
    );
}
