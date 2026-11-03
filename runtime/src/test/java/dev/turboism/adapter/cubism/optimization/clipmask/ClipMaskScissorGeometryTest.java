package dev.turboism.adapter.cubism.optimization.clipmask;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ClipMaskScissorGeometryTest {

    private static final float[] IDENTITY = {
        1.0f, 0.0f, 0.0f, 0.0f,
        0.0f, 1.0f, 0.0f, 0.0f,
        0.0f, 0.0f, 1.0f, 0.0f,
        0.0f, 0.0f, 0.0f, 1.0f
    };

    @Test
    void localBoundsReadsStepTwoAndThree() {
        final float[] bounds = new float[6];
        assertTrue(ClipMaskScissorGeometry.localBounds(new float[] {-2f, 3f, 4f, -7f, 5f, 6f}, 2, bounds));
        assertArrayEquals(new float[] {-2f, -7f, 0f, 5f, 6f, 0f}, bounds);

        assertTrue(ClipMaskScissorGeometry.localBounds(new float[] {-2f, 3f, 9f, 4f, -7f, -5f}, 3, bounds));
        assertArrayEquals(new float[] {-2f, -7f, -5f, 4f, 3f, 9f}, bounds);

        assertFalse(ClipMaskScissorGeometry.localBounds(new float[0], 2, bounds));
        assertFalse(ClipMaskScissorGeometry.localBounds(new float[] {Float.NaN, 0f}, 2, bounds));
        assertFalse(ClipMaskScissorGeometry.localBounds(new float[] {1f}, 2, bounds));
    }

    @Test
    void projectBoxMapsIdentityClipSpaceToViewport() {
        final float[] bounds = {-1f, -1f, 0f, 1f, 1f, 0f};
        final int[] viewport = {10, 20, 100, 50};
        final float[] screen = new float[4];
        assertTrue(ClipMaskScissorGeometry.projectBox(bounds, IDENTITY, IDENTITY, IDENTITY, viewport, screen));
        assertArrayEquals(new float[] {10f, 20f, 110f, 70f}, screen);

        final int[] rect = new int[4];
        ClipMaskScissorGeometry.screenToRect(screen, rect, viewport);
        assertArrayEquals(new int[] {10, 20, 110, 70}, rect, "the guard band is clipped to the viewport");
    }

    @Test
    void projectBoxRejectsCornersBehindTheCamera() {
        final float[] behind = {
            1.0f, 0.0f, 0.0f, 0.0f,
            0.0f, 1.0f, 0.0f, 0.0f,
            0.0f, 0.0f, 1.0f, 0.0f,
            0.0f, 0.0f, 0.0f, -1.0f
        };
        assertFalse(ClipMaskScissorGeometry.projectBox(
                new float[] {-1f, -1f, 0f, 1f, 1f, 0f},
                IDENTITY,
                IDENTITY,
                behind,
                new int[] {0, 0, 10, 10},
                new float[4]));
    }

    @Test
    void rectMathUnionsClampsAndMeasures() {
        final int[] first = {10, 20, 30, 40};
        final int[] second = {5, 25, 20, 60};
        final int[] union = new int[4];
        ClipMaskScissorGeometry.union(first, second, union);
        assertArrayEquals(new int[] {5, 20, 30, 60}, union);
        assertEquals(1000L, ClipMaskScissorGeometry.area(union));

        final int[] viewport = {0, 0, 100, 100};
        assertFalse(ClipMaskScissorGeometry.coversViewport(union, viewport));
        final int[] full = {0, 0, 100, 100};
        assertTrue(ClipMaskScissorGeometry.coversViewport(full, viewport));

        assertEquals(0L, ClipMaskScissorGeometry.area(new int[] {10, 10, 10, 50}));
    }

    @Test
    void screenToRectAddsGuardBandAndClamps() {
        final int[] rect = new int[4];
        ClipMaskScissorGeometry.screenToRect(
                new float[] {-4.2f, 99.8f, 104.1f, 130.0f}, rect, new int[] {0, 0, 100, 100});
        assertArrayEquals(new int[] {0, 98, 100, 100}, rect);
    }
}
