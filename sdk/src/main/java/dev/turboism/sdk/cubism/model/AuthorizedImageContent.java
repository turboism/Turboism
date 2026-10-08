package dev.turboism.sdk.cubism.model;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Authorized PNG image content for one model-image registration.
 *
 * <p>The value carries a defensive copy of the caller's bytes plus a free-text
 * source description for diagnostics; it never carries a filesystem path or a
 * native host object. Construction validates the container budget and the PNG
 * header (signature and IHDR dimensions); it does not decode the image. The
 * single-use token semantics make one authorized grant register exactly once:
 * a second registration attempt with the same instance is rejected.</p>
 */
public final class AuthorizedImageContent {

    /** Maximum accepted PNG container size in bytes. */
    public static final int MAX_PNG_BYTES = 8 * 1024 * 1024;
    /** Maximum decoded RGBA expansion in bytes. */
    public static final int MAX_RGBA_BYTES = 4 * 1024 * 1024;
    /** Required decoded width in pixels for this slice. */
    public static final int REQUIRED_WIDTH = 256;
    /** Required decoded height in pixels for this slice. */
    public static final int REQUIRED_HEIGHT = 128;

    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final int IHDR_LENGTH = 13;
    private static final int IHDR_OFFSET = 8;
    private static final int IHDR_WIDTH_OFFSET = IHDR_OFFSET + 8;
    private static final int IHDR_HEIGHT_OFFSET = IHDR_OFFSET + 12;

    private final byte[] bytes;
    private final String sourceDescription;
    private final int width;
    private final int height;
    private final AtomicBoolean consumed = new AtomicBoolean();

    private AuthorizedImageContent(final byte[] bytes, final String sourceDescription) {
        this.bytes = bytes;
        this.sourceDescription = sourceDescription;
        this.width = int32(bytes, IHDR_WIDTH_OFFSET);
        this.height = int32(bytes, IHDR_HEIGHT_OFFSET);
    }

    /**
     * Creates one authorized image grant from caller-owned bytes.
     *
     * @param pngBytes PNG container bytes; defensively copied before storage
     * @param sourceDescription free-text origin description for diagnostics
     * @throws IllegalArgumentException when the container exceeds the byte
     *     budget, is not a PNG, or does not declare exactly
     *     {@value #REQUIRED_WIDTH}×{@value #REQUIRED_HEIGHT}
     */
    public static AuthorizedImageContent of(
        final byte[] pngBytes,
        final String sourceDescription
    ) {
        return ofDimensions(pngBytes, sourceDescription, REQUIRED_WIDTH, REQUIRED_HEIGHT);
    }

    /**
     * Authorizes a PNG with explicitly declared positive dimensions within the RGBA budget.
     * Source model images need not have atlas-page dimensions. This permits lossless
     * cropping or edge padding; it does not resample or decode the PNG.
     * The runtime must verify decoded dimensions against this declaration.
     */
    public static AuthorizedImageContent ofDimensions(
        final byte[] pngBytes, final String sourceDescription, final int width, final int height
    ) {
        if (width <= 0 || height <= 0 || (long) width * height > MAX_RGBA_BYTES / 4L) {
            throw new IllegalArgumentException("image dimensions must be positive within the RGBA budget");
        }
        final byte[] copy = Objects.requireNonNull(pngBytes, "pngBytes").clone();
        if (copy.length == 0) {
            throw new IllegalArgumentException("image content must not be empty");
        }
        if (copy.length > MAX_PNG_BYTES) {
            throw new IllegalArgumentException(
                "image content exceeds the " + MAX_PNG_BYTES + "-byte PNG container budget"
            );
        }
        validatePngHeader(copy, width, height);
        final String description = Objects.requireNonNull(sourceDescription, "sourceDescription");
        if (description.length() > 256) {
            throw new IllegalArgumentException("sourceDescription must not exceed 256 characters");
        }
        if (description.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("sourceDescription must not contain control characters");
        }
        return new AuthorizedImageContent(copy, description);
    }

    /** Validates the PNG signature and IHDR dimensions without decoding. */
    private static void validatePngHeader(final byte[] bytes, final int expectedWidth, final int expectedHeight) {
        if (bytes.length < IHDR_OFFSET + 8 + IHDR_LENGTH) {
            throw new IllegalArgumentException("image content is not a well-formed PNG");
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (bytes[index] != PNG_SIGNATURE[index]) {
                throw new IllegalArgumentException("image content is not a well-formed PNG");
            }
        }
        final int ihdrLength = int32(bytes, IHDR_OFFSET);
        if (ihdrLength != IHDR_LENGTH
            || bytes[IHDR_OFFSET + 4] != 'I' || bytes[IHDR_OFFSET + 5] != 'H'
            || bytes[IHDR_OFFSET + 6] != 'D' || bytes[IHDR_OFFSET + 7] != 'R') {
            throw new IllegalArgumentException("image content is not a well-formed PNG");
        }
        final int width = int32(bytes, IHDR_WIDTH_OFFSET);
        final int height = int32(bytes, IHDR_HEIGHT_OFFSET);
        if (width != expectedWidth || height != expectedHeight) {
            throw new IllegalArgumentException(
                "image dimensions must match declared " + expectedWidth + "x" + expectedHeight
            );
        }
        if ((long) width * height * 4L > MAX_RGBA_BYTES) {
            throw new IllegalArgumentException(
                "decoded image exceeds the " + MAX_RGBA_BYTES + "-byte RGBA budget"
            );
        }
    }

    private static int int32(final byte[] bytes, final int offset) {
        return ((bytes[offset] & 0xFF) << 24)
            | ((bytes[offset + 1] & 0xFF) << 16)
            | ((bytes[offset + 2] & 0xFF) << 8)
            | (bytes[offset + 3] & 0xFF);
    }

    /** Declared PNG width, verified again after native decoding. */
    public int width() { return width; }

    /** Declared PNG height, verified again after native decoding. */
    public int height() { return height; }

    /** Container size in bytes. */
    public int byteCount() {
        return bytes.length;
    }

    /** Free-text origin description supplied at authorization. */
    public String sourceDescription() {
        return sourceDescription;
    }

    /**
     * Returns a fresh defensive copy of the authorized bytes.
     *
     * <p>Callers that hand bytes to stateful native constructors must use this
     * copy: the native side retains the array reference, so the value object
     * must never leak its internal array.</p>
     */
    public byte[] copyBytes() {
        return bytes.clone();
    }

    /**
     * Marks this grant consumed. Package-private authorization state is not
     * exposed; the runtime claims the grant once per registration.
     *
     * @throws IllegalStateException when this grant was already consumed
     */
    public void claim() {
        if (!consumed.compareAndSet(false, true)) {
            throw new IllegalStateException("image content grant is already consumed");
        }
    }

    /** Returns whether this grant has already been consumed. */
    public boolean consumed() {
        return consumed.get();
    }
}
