package dev.turboism.validation.atlasimage.t038;

/**
 * Host-free boundary helper used by the owned bytecode fixture.
 *
 * <p>The helper contains only scalar checks, array identity/length checks, and
 * bound arithmetic. It never reads a pixel and never mutates either array.</p>
 */
public final class T038ArrayHelper {
    public static final String INTERNAL_NAME =
        "dev/turboism/validation/atlasimage/t038/T038ArrayHelper";
    public static final String BINARY_NAME = INTERNAL_NAME.replace('/', '.');
    public static final String BOUNDS_DESCRIPTOR = "(II[III[IIIIIZ)J";

    private static final long MAX_INT = Integer.MAX_VALUE;

    private T038ArrayHelper() {
    }

    /**
     * Returns packed loop bounds. Rejected inputs retain the original full
     * bounds; the check is side-effect free and does not scan source pixels.
     */
    public static long bounds(
        final int kx,
        final int ky,
        final int[] source,
        final int sourceWidth,
        final int sourceHeight,
        final int[] destination,
        final int stride,
        final int fullWidth,
        final int fullHeight,
        final int padding,
        final boolean optimize
    ) {
        if (!optimize || !isAdmitted(
            kx, ky, source, sourceWidth, sourceHeight, destination, stride,
            fullWidth, fullHeight, padding
        )) {
            return pack(fullWidth, fullHeight);
        }
        final int loopWidth = Math.min(fullWidth, ceilDivPositive(sourceWidth, kx));
        final int loopHeight = Math.min(fullHeight, ceilDivPositive(sourceHeight, ky));
        return pack(loopWidth, loopHeight);
    }

    /** Full-loop admission gate. It is intentionally independent of assertion mode. */
    public static boolean isAdmitted(
        final int kx,
        final int ky,
        final int[] source,
        final int sourceWidth,
        final int sourceHeight,
        final int[] destination,
        final int stride,
        final int fullWidth,
        final int fullHeight,
        final int padding
    ) {
        if (source == null || destination == null || source == destination) {
            return false;
        }
        if (kx <= 0 || ky <= 0 || !isPowerOfTwo(kx) || !isPowerOfTwo(ky)) {
            return false;
        }
        if (sourceWidth <= 0 || sourceHeight <= 0 || fullWidth <= 0 || fullHeight <= 0) {
            return false;
        }
        if (stride <= 0 || padding < 0) {
            return false;
        }
        final long expectedWidth = alignUp(sourceWidth, kx);
        final long expectedHeight = alignUp(sourceHeight, ky);
        if (expectedWidth != fullWidth || expectedHeight != fullHeight
            || expectedWidth <= 0L || expectedHeight <= 0L
            || expectedWidth > MAX_INT || expectedHeight > MAX_INT) {
            return false;
        }
        final long fullArea = (long) fullWidth * (long) fullHeight;
        if (fullArea <= 0L || fullArea > MAX_INT || destination.length < fullArea) {
            return false;
        }
        final long sourceArea = (long) sourceWidth * (long) sourceHeight;
        if (sourceArea <= 0L || sourceArea > MAX_INT || source.length < sourceArea) {
            return false;
        }
        final long blockArea = (long) kx * (long) ky;
        if (blockArea <= 0L || blockArea > MAX_INT) {
            return false;
        }
        final long maxXBlockStart = (long) (fullWidth - 1) * kx;
        final long maxXBlockAfterAdd = maxXBlockStart + kx;
        final long maxXBlockEnd = maxXBlockAfterAdd - 1L;
        final long maxYBlockStart = (long) (fullHeight - 1) * ky;
        final long maxYBlockAfterAdd = maxYBlockStart + ky;
        final long maxYBlockEnd = maxYBlockAfterAdd - 1L;
        if (maxXBlockStart < 0L || maxXBlockStart > MAX_INT
            || maxXBlockAfterAdd < 0L || maxXBlockAfterAdd > MAX_INT
            || maxXBlockEnd < 0L || maxXBlockEnd > MAX_INT
            || maxYBlockStart < 0L || maxYBlockStart > MAX_INT
            || maxYBlockAfterAdd < 0L || maxYBlockAfterAdd > MAX_INT
            || maxYBlockEnd < 0L || maxYBlockEnd > MAX_INT) {
            return false;
        }
        final long maxSamples = Math.min((long) kx, sourceWidth)
            * Math.min((long) ky, sourceHeight);
        if (maxSamples > MAX_INT / 255L || maxSamples > MAX_INT / (255L * 255L)) {
            return false;
        }
        final long paddedMaxX = (long) fullWidth - 1L + padding;
        final long paddedMaxY = (long) fullHeight - 1L + padding;
        final long requiredStride = (long) fullWidth + padding;
        if (paddedMaxX > MAX_INT || paddedMaxY > MAX_INT
            || requiredStride > MAX_INT || stride < requiredStride) {
            return false;
        }
        final long maxRowOffset = paddedMaxY * stride;
        final long maxDestinationIndex = maxRowOffset + paddedMaxX;
        return maxRowOffset <= MAX_INT
            && maxDestinationIndex >= 0L
            && maxDestinationIndex < destination.length;
    }

    public static int unpackWidth(final long packed) {
        return (int) packed;
    }

    public static int unpackHeight(final long packed) {
        return (int) (packed >>> 32);
    }

    private static long pack(final int width, final int height) {
        return ((long) height << 32) | (width & 0xffffffffL);
    }

    private static long alignUp(final int value, final int factor) {
        return ((long) value + factor - 1L) & ~((long) factor - 1L);
    }

    private static int ceilDivPositive(final int value, final int divisor) {
        final long quotient = (long) value / divisor;
        final long result = value % divisor == 0 ? quotient : quotient + 1L;
        return (int) result;
    }

    private static boolean isPowerOfTwo(final int value) {
        return (value & (value - 1)) == 0;
    }
}
