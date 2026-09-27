package dev.turboism.validation.atlasimage.t033;

import dev.turboism.validation.atlasimage.t038.T038ArrayHelper;

/**
 * Retained T030/T033 facade for the extracted host-free helper.
 *
 * <p>The negative-control switch belongs only to the old rejection test; the
 * real admission and bound arithmetic live in {@code T038ArrayHelper}.</p>
 */
public final class T033FixtureAdmission {
    private static boolean negativeControlReturnTrimmed;

    private T033FixtureAdmission() {
    }

    static void setNegativeControlReturnTrimmed(final boolean enabled) {
        negativeControlReturnTrimmed = enabled;
    }

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
        final boolean admitted = T038ArrayHelper.isAdmitted(
            kx, ky, source, sourceWidth, sourceHeight, destination, stride,
            fullWidth, fullHeight, padding
        );
        if (!optimize || !admitted) {
            if (negativeControlReturnTrimmed && optimize) {
                return pack(0, 0);
            }
            return pack(fullWidth, fullHeight);
        }
        return T038ArrayHelper.bounds(
            kx, ky, source, sourceWidth, sourceHeight, destination, stride,
            fullWidth, fullHeight, padding, true
        );
    }

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
        return T038ArrayHelper.isAdmitted(
            kx, ky, source, sourceWidth, sourceHeight, destination, stride,
            fullWidth, fullHeight, padding
        );
    }

    static int unpackWidth(final long packed) {
        return T038ArrayHelper.unpackWidth(packed);
    }

    static int unpackHeight(final long packed) {
        return T038ArrayHelper.unpackHeight(packed);
    }

    private static long pack(final int width, final int height) {
        return ((long) height << 32) | (width & 0xffffffffL);
    }
}
