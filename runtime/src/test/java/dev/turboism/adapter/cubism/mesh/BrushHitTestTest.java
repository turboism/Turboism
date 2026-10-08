package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import org.junit.jupiter.api.Test;

class BrushHitTestTest {
    @Test
    void circleIncludesItsBoundaryAndReturnsAscendingIndices() {
        List<Point2> points = List.of(new Point2(5, 0), new Point2(0, 0), new Point2(5.01f, 0));
        assertEquals(List.of(0, 1), BrushHitTest.indicesInCircle(points, 0, 0, 5));
        assertTrue(BrushHitTest.contains(3, 4, 0, 0, 5));
        assertFalse(BrushHitTest.contains(3.1f, 4, 0, 0, 5));
    }

    @Test
    void capsuleCoversLongDragWithoutSamplingGapsAndDegeneratesToCircle() {
        List<Point2> points = List.of(new Point2(0, 3), new Point2(50, 3), new Point2(100, 3), new Point2(50, 3.01f));
        assertEquals(List.of(0, 1, 2), BrushHitTest.indicesWithinStroke(points, 0, 0, 100, 0, 3));
        assertTrue(BrushHitTest.withinStroke(3, 4, 0, 0, 0, 0, 5));
    }
}
