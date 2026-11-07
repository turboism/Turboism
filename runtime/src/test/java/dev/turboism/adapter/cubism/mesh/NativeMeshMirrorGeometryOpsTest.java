package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class NativeMeshMirrorGeometryOpsTest {

    /**
     * The crossing test decides which existing inner edges a new geometric layout removes, so a
     * segment that only shares an endpoint or lies on the same line must never count as crossing.
     */
    @Test
    void detectsProperCrossingsOnly() {
        assertTrue(NativeMeshMirrorGeometryOps.segmentsIntersect(0.0f, 0.0f, 2.0f, 2.0f, 0.0f, 2.0f, 2.0f, 0.0f));
        assertTrue(NativeMeshMirrorGeometryOps.segmentsIntersect(0.0f, 2.0f, 2.0f, 0.0f, 0.0f, 0.0f, 2.0f, 2.0f));
    }

    @Test
    void rejectsParallelAndDisjointSegments() {
        assertFalse(NativeMeshMirrorGeometryOps.segmentsIntersect(0.0f, 0.0f, 2.0f, 0.0f, 0.0f, 1.0f, 2.0f, 1.0f));
        assertFalse(NativeMeshMirrorGeometryOps.segmentsIntersect(0.0f, 0.0f, 1.0f, 0.0f, 5.0f, 0.0f, 6.0f, 0.0f));
    }

    /** A shared endpoint is a touch, not a crossing; native layouts rely on that distinction. */
    @Test
    void rejectsSegmentsThatOnlyTouch() {
        assertFalse(NativeMeshMirrorGeometryOps.segmentsIntersect(0.0f, 0.0f, 1.0f, 1.0f, 1.0f, 1.0f, 2.0f, 0.0f));
        assertFalse(NativeMeshMirrorGeometryOps.segmentsIntersect(0.0f, 0.0f, 2.0f, 0.0f, 1.0f, 0.0f, 3.0f, 0.0f));
    }
}
