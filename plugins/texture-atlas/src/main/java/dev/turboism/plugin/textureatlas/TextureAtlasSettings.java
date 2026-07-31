package dev.turboism.plugin.textureatlas;

import java.util.Objects;

/** Persisted plugin-owned automatic-layout policy (persisted through the Turboism config registry). */
public record TextureAtlasSettings(
    TextureAtlasLayoutMode layoutMode,
    TextureAtlasLayoutAlgorithm algorithm,
    boolean parallel
) {
    public TextureAtlasSettings {
        Objects.requireNonNull(layoutMode, "layoutMode");
        Objects.requireNonNull(algorithm, "algorithm");
    }

    public static TextureAtlasSettings defaults() {
        return new TextureAtlasSettings(
            TextureAtlasLayoutMode.PART_BUCKET,
            TextureAtlasLayoutAlgorithm.MAXRECTS,
            false
        );
    }
}
