package dev.turboism.sdk.cubism.textureatlas;


import java.util.Objects;

/** One immutable texture input for an atlas layout planner. */
public record TextureAtlasLayoutItem(String textureId, int width, int height) {

    public TextureAtlasLayoutItem {
        textureId = requireId(textureId);
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Texture dimensions must be positive.");
        }
    }

    private static String requireId(final String value) {
        Objects.requireNonNull(value, "textureId");
        final String normalized = value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Texture ID must not be blank.");
        }
        return normalized;
    }
}
