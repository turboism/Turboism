package dev.turboism.adapter.cubism.optimization.atlas;

import java.awt.image.BufferedImage;

/**
 * Row-major entrypoint for {@code jp.noids.graphics.h.a(BufferedImage, int)} ({@code edgeFill}),
 * delegating pixel sweeps to {@link DirectEdgeFillOp}.
 */
public final class TextureAtlasEdgeFillDelegate {

    private TextureAtlasEdgeFillDelegate() {}

    /**
     * Executes bit-identical horizontal and vertical edge fill in row-major memory order.
     *
     * @param image target {@code BufferedImage}
     * @param distance fill distance threshold
     * @return {@code true} when backed by {@code DataBufferInt}, or {@code false} to use native
     */
    public static boolean tryEdgeFill(final BufferedImage image, final int distance) {
        return DirectEdgeFillOp.tryEdgeFill(image, distance);
    }

    static void edgeFillRowMajor(final int[] pixels, final int width, final int height, final int distance) {
        DirectEdgeFillOp.edgeFillRowMajor(pixels, width, height, distance);
    }
}
