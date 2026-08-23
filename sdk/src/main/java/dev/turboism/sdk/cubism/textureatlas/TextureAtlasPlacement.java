package dev.turboism.sdk.cubism.textureatlas;


import java.util.Objects;

/** One immutable placement inside a complete atlas layout plan. */
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
        if (rotated) {
            throw new IllegalArgumentException("Rotated placements are not supported by this Preview tracer.");
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
