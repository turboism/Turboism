package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.CubismEditor;

/** Final pixel dimensions of one complete-atlas page. */
@CubismEditor
public record TextureAtlasPageSize(int width, int height) {
    public TextureAtlasPageSize {
        if (width < 1 || height < 1 || width > 16384 || height > 16384) {
            throw new IllegalArgumentException("Atlas dimensions must be between 1 and 16384 pixels.");
        }
    }
}
