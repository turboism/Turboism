package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class BrushStrokeAccumulatorTest {
    @Test
    void snapshotsRadiusAtPressUnionsHitsAndConsumesReleaseExactlyOnce() {
        BrushStrokeAccumulator stroke = new BrushStrokeAccumulator();
        stroke.press(32);
        stroke.addHits(List.of(4, 1));
        stroke.addHits(List.of(2, 4));

        assertTrue(stroke.active());
        assertEquals(32, stroke.radiusPixels());
        assertEquals(List.of(1, 2, 4), stroke.release());
        assertFalse(stroke.active());
        assertEquals(List.of(), stroke.release());
    }

    @Test
    void cancelDiscardsPendingHitsWithoutCommit() {
        BrushStrokeAccumulator stroke = new BrushStrokeAccumulator();
        stroke.press(64);
        stroke.addHits(List.of(1, 3));
        stroke.cancel();
        assertEquals(List.of(), stroke.release());
    }
}
