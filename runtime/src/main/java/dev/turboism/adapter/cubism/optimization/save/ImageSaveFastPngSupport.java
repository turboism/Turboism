package dev.turboism.adapter.cubism.optimization.save;

import dev.turboism.adapter.cubism.exportperf.FastPngEncoder;
import dev.turboism.adapter.cubism.exportperf.FastPngExporter;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Provides lossless PNG encoding fast paths for {@code UtImage.saveImage_exe} and dirty
 * {@code CImageResource} save pre-encoding.
 */
public final class ImageSaveFastPngSupport {

    private static final String PNG_FORMAT = "png";
    private static final int FILE_BUFFER_BYTES = 64 * 1024;

    private ImageSaveFastPngSupport() {}

    /**
     * Attempts fast lossless PNG writing for {@code UtImage.saveImage_exe}.
     *
     * @param image source image
     * @param output target {@link OutputStream} or {@link File}
     * @param format image format name
     * @return {@code true} if written directly, {@code false} to fall back to ImageIO
     */
    public static boolean trySavePng(final BufferedImage image, final Object output, final String format) {
        if (image == null || output == null || format == null || !PNG_FORMAT.equalsIgnoreCase(format)) {
            return false;
        }
        try {
            if (output instanceof OutputStream out) {
                if (FastPngExporter.tryConsumePreparedPng(out)) {
                    out.flush();
                    return true;
                }
                if (FastPngEncoder.writeLosslessIfSupported(image, out)) {
                    out.flush();
                    return true;
                }
                return false;
            }
            if (output instanceof File file && FastPngEncoder.isLosslessSupported(image)) {
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), FILE_BUFFER_BYTES)) {
                    if (FastPngEncoder.writeLosslessIfSupported(image, out)) {
                        out.flush();
                        return true;
                    }
                }
            }
        } catch (final IOException ignored) {
            return false;
        }
        return false;
    }

    /**
     * Encodes {@code image} directly into PNG bytes when its pixel layout is supported.
     *
     * @param image source image
     * @return encoded PNG bytes, or {@code null} when fallback encoding is required
     * @throws IOException if byte serialization fails
     */
    public static byte[] encodePngIfSupported(final BufferedImage image) throws IOException {
        return FastPngEncoder.encodeLosslessIfSupported(image);
    }
}
