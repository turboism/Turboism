package dev.turboism.sdk.cubism.textureatlas;


import java.util.List;
import java.util.Objects;

/**
 * Immutable summary of a texture atlas (or one selected texture within it):
 * the model-image count, the page count, and the model-image size distribution.
 */
public record TextureAtlasSummary(
    int imageCount,
    int pageCount,
    List<TextureAtlasSizeBucket> sizeDistribution
) {
    public TextureAtlasSummary {
        if (imageCount < 0 || pageCount < 0) {
            throw new IllegalArgumentException("Counts must not be negative.");
        }
        Objects.requireNonNull(sizeDistribution, "sizeDistribution");
        sizeDistribution = List.copyOf(sizeDistribution);
    }
}
