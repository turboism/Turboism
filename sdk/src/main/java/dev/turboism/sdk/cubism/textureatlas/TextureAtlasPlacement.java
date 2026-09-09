package dev.turboism.sdk.cubism.textureatlas;


import java.util.Objects;

/** Final pixel bounds; rotated means a positive quarter-turn before translating into these bounds. */
public record TextureAtlasPlacement(
    String textureId,
    int pageIndex,
    int x,
    int y,
    int width,
    int height,
    boolean rotated
) {

    public TextureAtlasPlacement {
        textureId = requireId(textureId);
        if (pageIndex < 0 || x < 0 || y < 0) {
            throw new IllegalArgumentException("Placement page and coordinates must not be negative.");
        }
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Placement dimensions must be positive.");
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
