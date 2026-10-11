package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SurfaceEditableTopologyPlanTest {
    private static ArtMeshGeometry source() {
        return new ArtMeshGeometry(
                List.of(new Point2(0, 0), new Point2(1, 0), new Point2(1, 1), new Point2(0, 1)),
                List.of(new Point2(.1f, .2f), new Point2(.3f, .4f), new Point2(.5f, .6f), new Point2(.7f, .8f)),
                List.of(0, 1, 2, 0, 2, 3));
    }

    private static final SurfaceEditableTopologyPlan.Limits LIMITS = new SurfaceEditableTopologyPlan.Limits(8, 16, 32);

    @Test
    void exactNativePreviewRetainsWholeInputIncludingSlotOrder() {
        var source = source();
        var plan = SurfaceEditableTopologyPlan.compile(
                source, LIMITS, g -> new ArtMeshSourceTriangulation(g.positions(), List.of(2, 3, 0, 1, 2, 0)));
        assertEquals(1, plan.nativeQueries());
        assertEquals(1, plan.parts().size());
        assertSame(source, plan.parts().get(0).geometry());
        assertEquals(List.of(0, 1, 2, 3), plan.parts().get(0).sourceSlots());
    }

    @Test
    void changedTopologySplitsContiguouslyAndPreservesEveryOriginalCornerAndUv() {
        var source = source();
        var plan = SurfaceEditableTopologyPlan.compile(
                source,
                LIMITS,
                g -> new ArtMeshSourceTriangulation(
                        g.positions(), g.triangleIndices().size() > 3 ? List.of() : g.triangleIndices()));
        assertEquals(3, plan.nativeQueries());
        assertEquals(6, plan.totalSlots());
        int next = 0;
        for (var part : plan.parts()) {
            assertEquals(next, part.firstFace());
            for (int corner = 0; corner < part.geometry().triangleIndices().size(); corner++) {
                int old = source.triangleIndices().get(part.firstFace() * 3 + corner);
                int local = part.geometry().triangleIndices().get(corner);
                assertEquals(old, part.sourceSlots().get(local));
                assertEquals(
                        source.positions().get(old), part.geometry().positions().get(local));
                assertEquals(source.uvs().get(old), part.geometry().uvs().get(local));
            }
            next = part.endFace();
        }
        assertEquals(2, next);
    }

    @Test
    void reversedWindingAndAddedOrMissingFacesNeverPass() {
        for (int mode = 0; mode < 3; mode++) {
            final int variant = mode;
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SurfaceEditableTopologyPlan.compile(source(), LIMITS, g -> {
                        var indices = new ArrayList<>(g.triangleIndices());
                        if (variant == 0) {
                            int first = indices.get(0);
                            indices.set(0, indices.get(1));
                            indices.set(1, first);
                        } else if (variant == 1) indices.addAll(g.triangleIndices());
                        else indices.clear();
                        return new ArtMeshSourceTriangulation(g.positions(), indices);
                    }));
        }
    }

    @Test
    void collapsedSourceUvFaceIsExplicitlyRejectedWithoutDroppingIt() {
        var original = source();
        var geometry = new ArtMeshGeometry(
                List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 0), new Point2(0, 1)),
                original.uvs(),
                original.triangleIndices());
        var failure = assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceEditableTopologyPlan.compile(
                        geometry, LIMITS, g -> new ArtMeshSourceTriangulation(g.positions(), List.of())));
        assertTrue(failure.getMessage().contains("original face 0"));
    }

    @Test
    void repeatedIndicesUseCyclicEquivalenceAndDuplicateFacesKeepMultiplicity() {
        var s = source();
        var geometry = new ArtMeshGeometry(s.positions(), s.uvs(), List.of(1, 2, 1, 1, 2, 1));
        var plan = SurfaceEditableTopologyPlan.compile(
                geometry, LIMITS, g -> new ArtMeshSourceTriangulation(g.positions(), List.of(2, 1, 1, 1, 1, 2)));
        assertEquals(1, plan.parts().size());
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceEditableTopologyPlan.compile(
                        geometry, LIMITS, g -> new ArtMeshSourceTriangulation(g.positions(), List.of(1, 2, 1))));
    }

    @Test
    void nativePositionChangeAndNativeFailureAreNotIgnored() {
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceEditableTopologyPlan.compile(source(), LIMITS, g -> {
                    var points = new ArrayList<>(g.positions());
                    points.set(0, new Point2(99, 99));
                    return new ArtMeshSourceTriangulation(points, g.triangleIndices());
                }));
        var failure = new IllegalStateException("native failure");
        assertSame(
                failure,
                assertThrows(
                        IllegalStateException.class,
                        () -> SurfaceEditableTopologyPlan.compile(source(), LIMITS, g -> {
                            throw failure;
                        })));
    }

    @Test
    void eachBudgetRejectsBeforeReturningPartialPlan() {
        for (var limits : List.of(
                new SurfaceEditableTopologyPlan.Limits(1, 16, 32),
                new SurfaceEditableTopologyPlan.Limits(8, 2, 32),
                new SurfaceEditableTopologyPlan.Limits(8, 16, 5)))
            assertThrows(
                    IllegalArgumentException.class,
                    () -> SurfaceEditableTopologyPlan.compile(
                            source(),
                            limits,
                            g -> new ArtMeshSourceTriangulation(
                                    g.positions(), g.triangleIndices().size() > 3 ? List.of() : g.triangleIndices())));
    }
}
