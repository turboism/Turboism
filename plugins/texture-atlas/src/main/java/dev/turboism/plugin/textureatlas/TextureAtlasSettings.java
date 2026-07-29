package dev.turboism.plugin.textureatlas;

import java.util.Objects;

public record TextureAtlasSettings(TextureAtlasLayoutMode layoutMode) {
    public TextureAtlasSettings {
        Objects.requireNonNull(layoutMode, "layoutMode");
    }

    public static TextureAtlasSettings defaults() {
        return new TextureAtlasSettings(TextureAtlasLayoutMode.PART_BUCKET);
    }
}
