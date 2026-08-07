package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan;

import java.util.Optional;

/** Exact-version provider boundary for validated complete atlas plans. */
public interface TextureAtlasLayoutProvider {

    Optional<TextureAtlasAuthoringState> current();

    ApplyOutcome apply(TextureAtlasAuthoringState expected, TextureAtlasLayoutPlan plan);

    enum ApplyOutcome {
        APPLIED,
        NO_CHANGE,
        REJECTED
    }
}
