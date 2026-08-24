package dev.turboism.sdk.cubism.screenshot;


import javax.imageio.ImageIO;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Objects;

/** Decodable PNG preview image with its pixel dimensions. */
public record ScreenshotImage(int width, int height, byte[] png) {
    private static final int MAX_PNG_BYTES = 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    public ScreenshotImage {
        png = Objects.requireNonNull(png, "png").clone();
        if (png.length == 0 || png.length > MAX_PNG_BYTES) {
            throw new IllegalArgumentException("png must contain between 1 and 1048576 bytes");
        }
        if (!startsWithPngSignature(png)) {
            throw new IllegalArgumentException("png must start with the PNG signature");
        }
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        try {
            final var decoded = ImageIO.read(new ByteArrayInputStream(png));
            if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                throw new IllegalArgumentException("png must be a readable PNG matching width and height");
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("png must be a readable PNG", failure);
        }
    }

    @Override
    public byte[] png() {
        return png.clone();
    }

    private static boolean startsWithPngSignature(final byte[] value) {
        if (value.length < PNG_SIGNATURE.length) return false;
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (value[index] != PNG_SIGNATURE[index]) return false;
        }
        return true;
    }
}
