package dev.turboism.plugin.atlasmaxrectsbssf;

import java.util.Objects;

/**
 * Persisted plugin-owned packing policy (persisted through the Turboism config
 * registry). Algorithm selection and the parallel-search flag are runtime-owned
 * selection state, not plugin configuration.
 */
public record TextureAtlasSettings(TextureAtlasLayoutMode layoutMode) {

    public TextureAtlasSettings {
        Objects.requireNonNull(layoutMode, "layoutMode");
    }

    /** @return the policy used before the user has saved any settings: part-bucket layout. */
    public static TextureAtlasSettings defaults() {
        return new TextureAtlasSettings(TextureAtlasLayoutMode.PART_BUCKET);
    }
}
