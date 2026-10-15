package dev.turboism.plugin.frameanimation.slice;

import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Objects;
import javax.imageio.ImageIO;

/**
 * Preprocesses frame images to conform to the SDK's image budget and format requirements.
 */
public final class FrameImageProcessor {

    /** Maximum allowed pixel count (width * height) per AuthorizedImageContent decoded RGBA budget. */
    public static final long MAX_PIXEL_COUNT = AuthorizedImageContent.MAX_RGBA_BYTES / 4L; // 1,048,576 pixels

    private FrameImageProcessor() {}

    /**
     * Ensures the image fits within the SDK RGBA budget by downscaling proportionately if necessary.
     *
     * @param source the input image
     * @return the original image if within budget, or a high-quality downscaled copy
     */
    public static BufferedImage fitWithinBudget(final BufferedImage source) {
        Objects.requireNonNull(source, "source");
        final int w = source.getWidth();
        final int h = source.getHeight();
        final long pixels = (long) w * h;

        if (pixels <= MAX_PIXEL_COUNT) {
            // Already within budget, ensure TYPE_INT_ARGB
            if (source.getType() == BufferedImage.TYPE_INT_ARGB) {
                return source;
            }
            final BufferedImage argb = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            final Graphics2D g2 = argb.createGraphics();
            try {
                g2.drawImage(source, 0, 0, null);
            } finally {
                g2.dispose();
            }
            return argb;
        }

        // Needs proportionate downscale
        final double scale = Math.sqrt((double) MAX_PIXEL_COUNT / pixels) * 0.999;
        final int targetW = Math.max(1, (int) Math.floor(w * scale));
        final int targetH = Math.max(1, (int) Math.floor(h * scale));

        final BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g2 = scaled.createGraphics();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.drawImage(source, 0, 0, targetW, targetH, null);
        } finally {
            g2.dispose();
        }

        return scaled;
    }

    /**
     * Encodes a BufferedImage to a PNG byte array.
     */
    public static byte[] toPngBytes(final BufferedImage image) {
        Objects.requireNonNull(image, "image");
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "PNG", out)) {
                throw new IllegalStateException("no PNG ImageWriter available");
            }
        } catch (IOException e) {
            throw new IllegalStateException("failed to encode image to PNG", e);
        }
        return out.toByteArray();
    }

    /**
     * Converts a BufferedImage into an {@link AuthorizedImageContent} grant, automatically
     * scaling down if it exceeds the RGBA budget.
     *
     * @param image input image
     * @param description origin description for diagnostics
     * @return an authorized image grant ready to be registered on the model
     */
    public static AuthorizedImageContent toAuthorizedImage(final BufferedImage image, final String description) {
        final BufferedImage fitted = fitWithinBudget(image);
        final byte[] bytes = toPngBytes(fitted);
        return AuthorizedImageContent.ofDimensions(bytes, description, fitted.getWidth(), fitted.getHeight());
    }
}
