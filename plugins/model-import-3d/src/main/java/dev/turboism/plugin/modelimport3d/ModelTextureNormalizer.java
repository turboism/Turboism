package dev.turboism.plugin.modelimport3d;

import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Bounded opaque PNG/JPEG to plain RGB PNG. Keeps decoded size; never resizes. */
final class ModelTextureNormalizer {
    private static final int MIN_IMAGE_BYTES = 8;
    private static final int BYTES_PER_PIXEL = 4;
    private static final int OPAQUE_ALPHA = 255;
    private static final int ALPHA_SHIFT = 24;

    private ModelTextureNormalizer() {}

    static byte[] png(byte[] input) throws IOException {
        if (input == null || input.length < MIN_IMAGE_BYTES || input.length > AuthorizedImageContent.MAX_PNG_BYTES)
            throw new IllegalArgumentException("Texture file size exceeds limit.");
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            var readers = ImageIO.getImageReaders(stream);
            if (!readers.hasNext()) throw new IllegalArgumentException("Texture must be PNG or JPEG.");
            var reader = readers.next();
            try {
                String format = reader.getFormatName();
                if (!format.equalsIgnoreCase("png")
                        && !format.equalsIgnoreCase("jpeg")
                        && !format.equalsIgnoreCase("jpg"))
                    throw new IllegalArgumentException("Texture must be PNG or JPEG.");
                reader.setInput(stream, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0
                        || h <= 0
                        || (w & (w - 1)) != 0
                        || (h & (h - 1)) != 0
                        || (long) w * h > AuthorizedImageContent.MAX_RGBA_BYTES / BYTES_PER_PIXEL)
                    throw new IllegalArgumentException(
                            "Texture dimensions must be powers of two and at most 1,048,576 decoded pixels.");
                var decoded = reader.read(0);
                var rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                for (int y = 0; y < h; y++)
                    for (int x = 0; x < w; x++) {
                        int pixel = decoded.getRGB(x, y);
                        if ((pixel >>> ALPHA_SHIFT) != OPAQUE_ALPHA)
                            throw new IllegalArgumentException("Transparent textures are not supported.");
                        rgb.setRGB(x, y, pixel);
                    }
                var output = new ByteArrayOutputStream();
                if (!ImageIO.write(rgb, "png", output)) throw new IOException("PNG encoder unavailable");
                byte[] png = output.toByteArray();
                if (png.length > AuthorizedImageContent.MAX_PNG_BYTES)
                    throw new IllegalArgumentException("Normalized PNG exceeds size limit.");
                return png;
            } finally {
                reader.dispose();
            }
        }
    }
}
