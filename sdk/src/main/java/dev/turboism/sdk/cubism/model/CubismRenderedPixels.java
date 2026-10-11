package dev.turboism.sdk.cubism.model;

import java.util.Arrays;
import java.util.Objects;
import dev.turboism.sdk.CubismEditor;

/**
 * Immutable Turboism-owned raster produced by a native Editor offscreen model render.
 *
 * <p>The array is defensively copied on construction and on every accessor so a
 * caller can never alias or mutate host/adapter internals. Bounds are hard limits:
 * each dimension is 1..2048 and the total pixel count never exceeds 4_194_304
 * (~16 MiB of {@code int}).</p>
 */
@CubismEditor({"5.2.03", "5.3.02", "5.3.03"})
public record CubismRenderedPixels(int width, int height, int[] argb) {

    public static final int MAX_DIMENSION = 2048;
    public static final int MAX_PIXELS = 4_194_304;

    public CubismRenderedPixels {
        if (width < 1 || width > MAX_DIMENSION) {
            throw new IllegalArgumentException("width out of bounds: " + width);
        }
        if (height < 1 || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("height out of bounds: " + height);
        }
        if ((long) width * (long) height > MAX_PIXELS) {
            throw new IllegalArgumentException("pixel budget exceeded: " + width + "x" + height);
        }
        Objects.requireNonNull(argb, "argb");
        if (argb.length != width * height) {
            throw new IllegalArgumentException(
                "argb length " + argb.length + " does not match " + width + "x" + height
            );
        }
        argb = argb.clone();
    }

    @Override public int[] argb() {
        return argb.clone();
    }

    /** Returns the packed ARGB value at ({@code x}, {@code y}), origin top-left. */
    public int argbAt(final int x, final int y) {
        if (x < 0 || x >= width || y < 0 || y >= height) {
            throw new IndexOutOfBoundsException("(" + x + ", " + y + ") outside " + width + "x" + height);
        }
        return argb[y * width + x];
    }

    @Override public boolean equals(final Object other) {
        return other instanceof CubismRenderedPixels pixels
            && pixels.width == width && pixels.height == height
            && Arrays.equals(pixels.argb, argb);
    }

    @Override public int hashCode() {
        return 31 * (31 * width + height) + Arrays.hashCode(argb);
    }

    @Override public String toString() {
        return "CubismRenderedPixels[" + width + "x" + height + "]";
    }
}
