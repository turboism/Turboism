package dev.turboism.plugin.parametersync.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SyncModeTest {

    @Test
    void normalizedRangeMapsDirectlyAcrossDifferentBounds() {
        final SyncMode mode = SyncMode.NORMALIZED_RANGE;
        // Source: [-30, 30], Target: [0, 1]
        assertEquals(0.0f, mode.computeTarget(-30.0f, -30.0f, 30.0f, 0.0f, 1.0f, 1.0f, 0.0f), 1e-4f);
        assertEquals(0.5f, mode.computeTarget(0.0f, -30.0f, 30.0f, 0.0f, 1.0f, 1.0f, 0.0f), 1e-4f);
        assertEquals(1.0f, mode.computeTarget(30.0f, -30.0f, 30.0f, 0.0f, 1.0f, 1.0f, 0.0f), 1e-4f);

        // Reverse: Target [0, 1] to Source [-30, 30]
        assertEquals(-30.0f, mode.computeSource(0.0f, -30.0f, 30.0f, 0.0f, 1.0f, 1.0f, 0.0f), 1e-4f);
        assertEquals(0.0f, mode.computeSource(0.5f, -30.0f, 30.0f, 0.0f, 1.0f, 1.0f, 0.0f), 1e-4f);
        assertEquals(30.0f, mode.computeSource(1.0f, -30.0f, 30.0f, 0.0f, 1.0f, 1.0f, 0.0f), 1e-4f);
    }

    @Test
    void normalizedInvertedReversesRange() {
        final SyncMode mode = SyncMode.NORMALIZED_INVERTED;
        // Source: [-1, 1], Target: [-1, 1]
        assertEquals(1.0f, mode.computeTarget(-1.0f, -1.0f, 1.0f, -1.0f, 1.0f, 1.0f, 0.0f), 1e-4f);
        assertEquals(0.0f, mode.computeTarget(0.0f, -1.0f, 1.0f, -1.0f, 1.0f, 1.0f, 0.0f), 1e-4f);
        assertEquals(-1.0f, mode.computeTarget(1.0f, -1.0f, 1.0f, -1.0f, 1.0f, 1.0f, 0.0f), 1e-4f);

        // Reverse
        assertEquals(-1.0f, mode.computeSource(1.0f, -1.0f, 1.0f, -1.0f, 1.0f, 1.0f, 0.0f), 1e-4f);
        assertEquals(0.0f, mode.computeSource(0.0f, -1.0f, 1.0f, -1.0f, 1.0f, 1.0f, 0.0f), 1e-4f);
        assertEquals(1.0f, mode.computeSource(-1.0f, -1.0f, 1.0f, -1.0f, 1.0f, 1.0f, 0.0f), 1e-4f);
    }

    @Test
    void directCopiesAndClamps() {
        final SyncMode mode = SyncMode.DIRECT;
        assertEquals(0.5f, mode.computeTarget(0.5f, 0.0f, 1.0f, 0.0f, 1.0f, 1.0f, 0.0f), 1e-4f);
        assertEquals(1.0f, mode.computeTarget(2.0f, 0.0f, 3.0f, 0.0f, 1.0f, 1.0f, 0.0f), 1e-4f);
        assertEquals(0.0f, mode.computeTarget(-1.0f, -2.0f, 2.0f, 0.0f, 1.0f, 1.0f, 0.0f), 1e-4f);

        // Reverse
        assertEquals(0.8f, mode.computeSource(0.8f, 0.0f, 1.0f, 0.0f, 2.0f, 1.0f, 0.0f), 1e-4f);
    }

    @Test
    void ratioAppliesLinearScaleAndOffset() {
        final SyncMode mode = SyncMode.RATIO;
        // target = 0.5 * source + 0.1
        assertEquals(0.6f, mode.computeTarget(1.0f, -10.0f, 10.0f, -10.0f, 10.0f, 0.5f, 0.1f), 1e-4f);
        // Reverse: source = (0.6 - 0.1) / 0.5 = 1.0
        assertEquals(1.0f, mode.computeSource(0.6f, -10.0f, 10.0f, -10.0f, 10.0f, 0.5f, 0.1f), 1e-4f);
    }

    @Test
    void degenerateRangeAvoidsDivisionByZero() {
        final SyncMode mode = SyncMode.NORMALIZED_RANGE;
        // When source range is degenerate, normalized t defaults to midpoint 0.5f (which is 5.0f in [0, 10])
        assertEquals(5.0f, mode.computeTarget(5.0f, 5.0f, 5.0f, 0.0f, 10.0f, 1.0f, 0.0f), 1e-4f);
    }
}
