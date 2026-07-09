package dev.turboism.sdk.cubism;

import java.util.List;

public record TextureAtlasSnapshot(
    String atlasId,
    int width,
    int height,
    List<String> textureIds
) {
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
