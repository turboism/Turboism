package dev.turboism.sdk.cubism.preview;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;
import java.util.Objects;

/**
 * One decoded preview frame: the pixels the host rendered, as a PNG, and their dimensions.
 *
 * <p>The value is deliberately an encoded image rather than a live image object, so a plugin
 * writes, queues or re-encodes it without ever holding a host buffer. The bytes come from a
 * runtime-side readback, so the record validates the container shape — the PNG signature and
 * the declared dimensions — and never re-decodes the pixels.</p>
 *
 * @param width the frame width in pixels, at least 1
 * @param height the frame height in pixels, at least 1
 * @param png the encoded frame, starting with the eight-byte PNG signature
 */
@Incubating
@CubismEditor
public record PreviewFrame(int width, int height, byte[] png) {

    /**
     * Upper bound on one encoded frame. A 4K preview encodes well below it; the bound exists so a
     * broken capture fails instead of buffering an unbounded array.
     */
    private static final int MAX_PNG_BYTES = 8 * 1024 * 1024;

    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    public PreviewFrame {
        png = Objects.requireNonNull(png, "png").clone();
        if (png.length < PNG_SIGNATURE.length || png.length > MAX_PNG_BYTES) {
            throw new IllegalArgumentException("png must contain between 1 and 8388608 bytes");
        }
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("width and height must be positive");
        }
        if (!startsWithPngSignature(png)) {
            throw new IllegalArgumentException("png must start with the PNG signature");
        }
    }

    @Override
    public byte[] png() {
        return png.clone();
    }

    private static boolean startsWithPngSignature(final byte[] value) {
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (value[index] != PNG_SIGNATURE[index]) {
                return false;
            }
        }
        return true;
    }
}
