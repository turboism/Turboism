package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MeshMirrorGeometryTest {

    @Test
    void reflectsProjectsAndHitsAgainstTheRotatedAxis() {
        final MeshMirrorGeometry.Line axis = MeshMirrorGeometry.rotatedAxis(0.0f, 0.0f, 0.0f, true, 45.0f);

        assertPoint(-1.0f, -1.0f, MeshMirrorGeometry.reflect(axis, 1.0f, 1.0f));
        assertPoint(1.0f, -1.0f, MeshMirrorGeometry.project(axis, 2.0f, 0.0f));
        assertEquals(0.0f, MeshMirrorGeometry.distance(axis, 2.0f, -2.0f), 0.0001f);
        assertEquals(true, MeshMirrorGeometry.hit(axis, 2.0f, -1.95f, 0.1f));
        assertEquals(false, MeshMirrorGeometry.hit(axis, 2.0f, -1.8f, 0.1f));
    }

    @Test
    void keepsTheNativeAxisAtZeroRotation() {
        final MeshMirrorGeometry.Line axis = MeshMirrorGeometry.rotatedAxis(3.0f, 4.0f, 7.0f, true, 0.0f);

        assertPoint(3.0f, 7.0f, axis.anchor());
        assertPoint(0.0f, 1.0f, axis.direction());
        assertPoint(5.0f, 4.0f, MeshMirrorGeometry.reflect(axis, 1.0f, 4.0f));
    }

    @Test
    void rotatesTheAnchorAroundThePivotAtNonZeroAngle() {
        final MeshMirrorGeometry.Line axis = MeshMirrorGeometry.rotatedAxis(2.0f, 4.0f, 6.0f, true, 45.0f);

        assertPoint(2.5858f, 4.5858f, axis.anchor());
        assertPoint(-0.7071f, 0.7071f, axis.direction());
    }

    @Test
    void drawnAxisCoversWideAndTallViewportsAcrossZoomPanAndRotation() {
        for (boolean vertical : new boolean[] {false, true}) {
            for (float zoom : new float[] {0.05f, 0.5f, 1.0f, 4.0f, 20.0f}) {
                for (float pan : new float[] {-3000.0f, 0.0f, 3000.0f}) {
                    final MeshMirrorGeometry.Point first =
                            new MeshMirrorGeometry.Point(pan - 960.0f / zoom, pan - 540.0f / zoom);
                    final MeshMirrorGeometry.Point opposite =
                            new MeshMirrorGeometry.Point(pan + 960.0f / zoom, pan + 540.0f / zoom);
                    for (int angle = -180; angle <= 180; angle++) {
                        final MeshMirrorGeometry.Line line =
                                MeshMirrorGeometry.rotatedAxis(0, 1000, 500, vertical, angle);
                        final MeshMirrorGeometry.Segment segment = MeshMirrorGeometry.covering(line, first, opposite);
                        final double extent = Math.hypot(
                                segment.end().x() - line.anchor().x(),
                                segment.end().y() - line.anchor().y());
                        for (float x : new float[] {first.x(), opposite.x()}) {
                            for (float y : new float[] {first.y(), opposite.y()}) {
                                final double projection = (x - line.anchor().x())
                                                * line.direction().x()
                                        + (y - line.anchor().y())
                                                * line.direction().y();
                                assertTrue(
                                        Math.abs(projection) <= extent + 0.02,
                                        "viewport cut off: zoom=" + zoom + " pan=" + pan + " angle=" + angle);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void assertPoint(
            final float expectedX, final float expectedY, final MeshMirrorGeometry.Point actual) {
        assertEquals(expectedX, actual.x(), 0.0001f);
        assertEquals(expectedY, actual.y(), 0.0001f);
    }
}
