package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;

import java.util.Optional;

/**
 * Reads and applies texture-atlas layouts. Outside a native packing invocation, targets require
 * a complete fixed-size atlas plan. Inside an invocation, {@code singlePageOptions} identifies
 * a current-page target: only its issued images may be placed, omitted images become native
 * overflow, and rotation/scaling must match the issued constraints. The caller must not plan
 * other pages or reuse a target after its originating invocation ends.
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public interface TextureAtlasLayoutService {

    Optional<TextureAtlasLayoutSnapshot> current();

    TextureAtlasLayoutApplyResult apply(
        TextureAtlasLayoutTarget target,
        TextureAtlasLayoutPlan plan
    );
}
