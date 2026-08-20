package dev.turboism.plugin.atlasmaxrectsbssf;

import java.util.Objects;

/** Persisted plugin-owned automatic-layout policy (persisted through the Turboism config registry). */
public record TextureAtlasSettings(
    TextureAtlasLayoutMode layoutMode,
    String algorithmId,
    boolean parallel
) {
    public TextureAtlasSettings {
        Objects.requireNonNull(layoutMode, "layoutMode");
        Objects.requireNonNull(algorithmId, "algorithmId");
        if (algorithmId.isBlank()) throw new IllegalArgumentException("algorithmId must not be blank");
    }

    /**
     * @return the policy used before the user has saved any settings: part-bucket layout, the
     *     MaxRects algorithm, and serial (non-parallel) packing
     */
    public static TextureAtlasSettings defaults() {
        return new TextureAtlasSettings(
            TextureAtlasLayoutMode.PART_BUCKET,
            TextureAtlasPlugin.ALGORITHM_MAXRECTS,
            false
        );
    }
}
