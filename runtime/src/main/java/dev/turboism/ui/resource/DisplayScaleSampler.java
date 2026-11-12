package dev.turboism.ui.resource;

import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.geom.AffineTransform;

/**
 * Maps the current display scale to the reviewed native-icon variant percentages.
 *
 * <p>The reviewed host sheets ship 100, 125, 150, 175 and 200 percent artwork. Selecting the
 * variant at or above the device scale keeps the artwork sharp: a larger source is downscaled,
 * while a smaller source would be upscaled and blurred. Pinning every session to 100 percent
 * therefore blurs the icon on any scaled display.</p>
 *
 * <p>Sampling reads only the default screen configuration. It performs no host, filesystem or
 * class-loader access, so it is safe off the event dispatch thread.</p>
 */
public final class DisplayScaleSampler {

    /** Reviewed variant percentages, ascending; the largest is the catalog ceiling. */
    private static final int[] REVIEWED_PERCENTS = {100, 125, 150, 175, 200};

    /** Percentage used when the platform reports no usable transform. */
    public static final int FALLBACK_PERCENT = 100;

    /** Tolerance for transforms that round just below an exact reviewed ratio. */
    private static final double SCALE_TOLERANCE = 0.01d;

    private DisplayScaleSampler() {}

    /** Samples the default screen device scale as a reviewed variant percentage. */
    public static int samplePercent() {
        return percentOf(deviceScale());
    }

    /** Converts a device scale factor to the smallest reviewed percentage that covers it. */
    public static int percentOf(final double deviceScale) {
        if (!(deviceScale > 0.0d) || Double.isInfinite(deviceScale)) {
            return FALLBACK_PERCENT;
        }
        for (final int percent : REVIEWED_PERCENTS) {
            if (deviceScale <= percent / 100.0d + SCALE_TOLERANCE) {
                return percent;
            }
        }
        return REVIEWED_PERCENTS[REVIEWED_PERCENTS.length - 1];
    }

    private static double deviceScale() {
        try {
            if (GraphicsEnvironment.isHeadless()) {
                return 1.0d;
            }
            final GraphicsDevice device =
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
            final GraphicsConfiguration configuration = device == null ? null : device.getDefaultConfiguration();
            final AffineTransform transform = configuration == null ? null : configuration.getDefaultTransform();
            return transform == null ? 1.0d : transform.getScaleX();
        } catch (RuntimeException unavailable) {
            return 1.0d;
        }
    }
}
