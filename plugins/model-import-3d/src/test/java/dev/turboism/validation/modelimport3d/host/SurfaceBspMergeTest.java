package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SurfaceBspMergeTest {
    private static final SurfaceBspPlan.Budget BUDGET = new SurfaceBspPlan.Budget(2000, 2000, 128, 2000000);
    private static final SurfaceBspMerge.Domain DOMAIN = new SurfaceBspMerge.Domain(30, 30);

    private static SurfaceSourceSplit.Source triangle(int id, int mesh, double z, double slopeX, double slopeY) {
        return new SurfaceSourceSplit.Source(
                mesh,
                mesh,
                0,
                id,
                List.of(
                        new SurfaceProjection.Vertex(-1, -1, z - slopeX - slopeY),
                        new SurfaceProjection.Vertex(1, -1, z + slopeX - slopeY),
                        new SurfaceProjection.Vertex(0, 1, z + slopeY)),
                List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1)));
    }

    @Test
    void invariantSubtreesBecomeOneDrawGroupWithoutChangingSourceCorners() {
        var sources = List.of(triangle(0, 0, 0, 0, 0), triangle(1, 0, 1, .1, .1), triangle(2, 0, 2, -.1, .1));
        var bsp = SurfaceBspPlan.compile(sources, BUDGET);
        var merged = SurfaceBspMerge.compile(bsp, DOMAIN, 65535);
        assertEquals(1, merged.patches().size());
        assertEquals(3, merged.patches().get(0).fragments().size());
        assertEquals(9, merged.patches().get(0).corners());
        verify(bsp, merged);
        for (var f : bsp.fragments())
            assertSame(sources.get(f.polygon().source().triangle()), f.polygon().source());
    }

    @Test
    void changingOrderBranchesSurviveAndMatchEveryOriginalTraversal() {
        var bsp = SurfaceBspPlan.compile(
                List.of(triangle(0, 0, 0, 2, 0), triangle(1, 0, 0, 0, 2), triangle(2, 0, 0, -2, -2)), BUDGET);
        var merged = SurfaceBspMerge.compile(bsp, DOMAIN, 65535);
        assertTrue(merged.patches().size() > 1);
        verify(bsp, merged);
        assertThrows(IllegalArgumentException.class, () -> merged.backToFront(30.1, 0));
        assertThrows(IllegalArgumentException.class, () -> merged.backToFront(0, Double.NaN));
    }

    @Test
    void materialPrimitiveIdentityAndNativeCornerBudgetRemainSeparateGroups() {
        var separate = SurfaceBspPlan.compile(List.of(triangle(0, 0, 0, 0, 0), triangle(0, 1, 1, 0, 0)), BUDGET);
        assertEquals(
                2, SurfaceBspMerge.compile(separate, DOMAIN, 65535).patches().size());
        var sources = new ArrayList<SurfaceSourceSplit.Source>();
        for (int i = 0; i < 40; i++) sources.add(triangle(i, 0, i, 0, 0));
        var bsp = SurfaceBspPlan.compile(sources, BUDGET);
        var merged = SurfaceBspMerge.compile(bsp, DOMAIN, 64);
        assertEquals(2, merged.patches().size());
        for (var patch : merged.patches()) assertTrue(patch.corners() <= 64);
        verify(bsp, merged);
        assertThrows(IllegalArgumentException.class, () -> SurfaceBspMerge.compile(bsp, DOMAIN, 3));
    }

    @Test
    void conservativeSignBoundsDoNotAdmitChangingOrNearTangentNormals() {
        assertEquals(1, SurfaceBspMerge.invariantSign(new SurfaceSourceSplit.Plane(0, 0, 1, 0), DOMAIN));
        assertEquals(-1, SurfaceBspMerge.invariantSign(new SurfaceSourceSplit.Plane(0, 0, -1, 0), DOMAIN));
        assertEquals(0, SurfaceBspMerge.invariantSign(new SurfaceSourceSplit.Plane(1, 0, 0, 0), DOMAIN));
        assertEquals(0, SurfaceBspMerge.invariantSign(new SurfaceSourceSplit.Plane(0, 1, 0, 0), DOMAIN));
        assertEquals(
                0,
                SurfaceBspMerge.invariantSign(
                        new SurfaceSourceSplit.Plane(1, 0, 1e-16, 0), new SurfaceBspMerge.Domain(0, 0)));
        var random = new Random(40719);
        int proven = 0;
        for (int i = 0; i < 200; i++) {
            var plane = new SurfaceSourceSplit.Plane(
                    random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, random.nextDouble() * 2 - 1, 0);
            int sign = SurfaceBspMerge.invariantSign(plane, DOMAIN);
            if (sign == 0) continue;
            proven++;
            for (int yaw = -30; yaw <= 30; yaw += 3)
                for (int pitch = -30; pitch <= 30; pitch += 3) {
                    double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
                    double dot = -plane.x() * Math.sin(y)
                            + plane.y() * Math.cos(y) * Math.sin(p)
                            + plane.z() * Math.cos(y) * Math.cos(p);
                    assertTrue(dot * sign > 0);
                }
        }
        assertTrue(proven > 20);
    }

    private static void verify(SurfaceBspPlan.Plan bsp, SurfaceBspMerge.Plan merged) {
        var seen = new java.util.HashSet<Integer>();
        int fragments = 0;
        for (var patch : merged.patches())
            for (int id : patch.fragments()) {
                assertTrue(seen.add(id));
                fragments++;
            }
        assertEquals(bsp.fragments().size(), fragments);
        for (int yaw = -30; yaw <= 30; yaw += 3)
            for (int pitch = -30; pitch <= 30; pitch += 3) {
                double y = Math.toRadians(yaw), p = Math.toRadians(pitch);
                var direction = new SurfaceProjection.Vertex(
                        -Math.sin(y), Math.cos(y) * Math.sin(p), Math.cos(y) * Math.cos(p));
                var flattened = new ArrayList<Integer>();
                for (int patch : merged.backToFront(yaw, pitch))
                    flattened.addAll(merged.patches().get(patch).fragments());
                assertEquals(
                        bsp.backToFront(direction), flattened, "merged groups must preserve original painter sequence");
            }
    }
}
