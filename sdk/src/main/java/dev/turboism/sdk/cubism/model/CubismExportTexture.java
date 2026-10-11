package dev.turboism.sdk.cubism.model;

import java.util.Arrays;
import java.util.Objects;
import dev.turboism.sdk.CubismEditor;

/** Owned, unscaled official export texture. Pixels are ARGB with a top-left origin. */
@CubismEditor({"5.3.02"})
public record CubismExportTexture(int width, int height, int[] argb) {
    public static final int MAX_DIMENSION = 8192;
    public static final int MAX_PIXELS = 16_777_216;

    public CubismExportTexture {
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION
            || (long) width * height > MAX_PIXELS) {
            throw new IllegalArgumentException("Export texture dimensions exceed the pixel budget.");
        }
        Objects.requireNonNull(argb, "argb");
        if (argb.length != width * height) {
            throw new IllegalArgumentException("Export pixel count differs from its dimensions.");
        }
        argb = argb.clone();
    }

    @Override public int[] argb() { return argb.clone(); }

    @Override public boolean equals(final Object other) {
        return other instanceof CubismExportTexture texture
            && width == texture.width && height == texture.height
            && Arrays.equals(argb, texture.argb);
    }

    @Override public int hashCode() {
        return 31 * (31 * width + height) + Arrays.hashCode(argb);
    }

    @Override public String toString() { return "CubismExportTexture[" + width + "x" + height + "]"; }
}
