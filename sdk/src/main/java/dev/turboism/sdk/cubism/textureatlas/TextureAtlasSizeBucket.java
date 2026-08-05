package dev.turboism.sdk.cubism.textureatlas;

import dev.turboism.sdk.PreviewApi;

import java.util.List;
import java.util.Objects;

/** One size bucket in a texture-atlas image size distribution. */
@PreviewApi
public record TextureAtlasSizeBucket(
    int width,
    int height,
    int count
) {
    public TextureAtlasSizeBucket {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Bucket dimensions must be positive.");
        }
        if (count < 1) {
            throw new IllegalArgumentException("Bucket count must be positive.");
        }
    }
}
