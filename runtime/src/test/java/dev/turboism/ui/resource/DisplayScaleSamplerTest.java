package dev.turboism.ui.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DisplayScaleSamplerTest {

    @Test
    void selectsTheSmallestReviewedVariantThatCoversTheDeviceScale() {
        assertEquals(100, DisplayScaleSampler.percentOf(1.0d));
        assertEquals(125, DisplayScaleSampler.percentOf(1.25d));
        assertEquals(150, DisplayScaleSampler.percentOf(1.5d));
        assertEquals(175, DisplayScaleSampler.percentOf(1.75d));
        assertEquals(200, DisplayScaleSampler.percentOf(2.0d));
    }

    @Test
    void neverSelectsAVariantBelowTheDeviceScale() {
        for (double scale = 1.0d; scale <= 2.0d; scale += 0.05d) {
            final int percent = DisplayScaleSampler.percentOf(scale);
            assertTrue(
                    percent / 100.0d >= scale - 0.01d,
                    "variant " + percent + "% must not upscale a " + scale + " device scale");
        }
    }

    @Test
    void intermediateAndOutOfRangeScalesStayInsideTheReviewedCatalog() {
        assertEquals(125, DisplayScaleSampler.percentOf(1.1d));
        assertEquals(150, DisplayScaleSampler.percentOf(1.4d));
        assertEquals(200, DisplayScaleSampler.percentOf(3.0d));
        assertEquals(DisplayScaleSampler.FALLBACK_PERCENT, DisplayScaleSampler.percentOf(0.0d));
        assertEquals(DisplayScaleSampler.FALLBACK_PERCENT, DisplayScaleSampler.percentOf(-1.0d));
        assertEquals(DisplayScaleSampler.FALLBACK_PERCENT, DisplayScaleSampler.percentOf(Double.NaN));
        assertEquals(DisplayScaleSampler.FALLBACK_PERCENT, DisplayScaleSampler.percentOf(Double.POSITIVE_INFINITY));
    }

    @Test
    void sampledPercentIsAlwaysACatalogVariant() {
        final int sampled = DisplayScaleSampler.samplePercent();
        assertTrue(
                sampled == 100 || sampled == 125 || sampled == 150 || sampled == 175 || sampled == 200,
                "unreviewed variant percentage: " + sampled);
    }
}
