package dev.turboism.sdk.cubism.textureatlas;

/** One size bucket in a texture-atlas image size distribution. */
public record TextureAtlasSizeBucket(int width, int height, int count) {
    public TextureAtlasSizeBucket {
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("Bucket dimensions must be positive.");
        }
        if (count < 1) {
            throw new IllegalArgumentException("Bucket count must be positive.");
        }
    }
}
