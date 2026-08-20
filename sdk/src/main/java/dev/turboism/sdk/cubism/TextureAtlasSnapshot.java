package dev.turboism.sdk.cubism;

import java.util.List;

/**
 * Immutable snapshot of a texture atlas page: its pixel extent and the textures packed into it.
 *
 * @param atlasId stable identifier of the atlas; must not be null or blank
 * @param width atlas width in pixels; must be at least 1
 * @param height atlas height in pixels; must be at least 1
 * @param textureIds unmodifiable copy of the identifiers of the textures packed into this atlas;
 *     carries no placement information, only membership
 */
public record TextureAtlasSnapshot(
    String atlasId,
    int width,
    int height,
    List<String> textureIds
) {
    /**
     * Validates the record components.
     *
     * @throws IllegalArgumentException if {@code atlasId} is null or blank, or either dimension is
     *     less than 1
     * @throws NullPointerException if {@code textureIds} is null
     */
    public TextureAtlasSnapshot {
        if (atlasId == null || atlasId.isBlank()) {
            throw new IllegalArgumentException("atlasId must not be null or blank");
        }
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("texture atlas dimensions must be positive");
        }
        textureIds = List.copyOf(textureIds);
    }
}
