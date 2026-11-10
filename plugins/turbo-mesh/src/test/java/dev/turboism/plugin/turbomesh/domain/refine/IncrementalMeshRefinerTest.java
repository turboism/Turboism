package dev.turboism.plugin.turbomesh.domain.refine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class IncrementalMeshRefinerTest {

    private static ArtMeshGeometry square() {
        final List<Point2> uv = List.of(
                new Point2(0f, 0f), new Point2(1f, 0f),
                new Point2(1f, 1f), new Point2(0f, 1f));
        final List<Integer> idx = List.of(0, 1, 2, 0, 2, 3);
        return new ArtMeshGeometry(uv, uv, idx);
    }

    private static ArtMeshGeometry grid2x2() {
        // 9 vertices on a 3x3 grid (0..1)
        final List<Point2> uv = new ArrayList<>();
        for (int y = 0; y < 3; y++) {
            for (int x = 0; x < 3; x++) {
                uv.add(new Point2(x * 0.5f, y * 0.5f));
            }
        }
        // 8 triangles across the 4 quad cells
        final List<Integer> idx = new ArrayList<>();
        for (int y = 0; y < 2; y++) {
            for (int x = 0; x < 2; x++) {
                final int bl = y * 3 + x;
                final int br = bl + 1;
                final int tl = bl + 3;
                final int tr = tl + 1;
                idx.add(bl);
                idx.add(br);
                idx.add(tr);
                idx.add(bl);
                idx.add(tr);
                idx.add(tl);
            }
        }
        return new ArtMeshGeometry(uv, uv, idx);
    }

    @Test
    void refinementIncreasesDensityWithoutMovingExistingVertices() {
        final ArtMeshGeometry src = square();
        final IncrementalMeshRefiner.RefinementOptions options =
                new IncrementalMeshRefiner.RefinementOptions(0.5, 1.5, 0.01, 10);
        final IncrementalMeshRefiner.RefinementResult result = IncrementalMeshRefiner.refine(src, null, options);

        assertTrue(result.addedPoints() > 0, "must add refinement points");
        assertTrue(result.addedTriangles() > 0, "must add refinement triangles");

        final ArtMeshGeometry refined = result.geometry();
        assertEquals(src.uvs().size() + result.addedPoints(), refined.uvs().size());

        // The original vertices must never move or change order.
        for (int i = 0; i < src.uvs().size(); i++) {
            assertEquals(src.uvs().get(i), refined.uvs().get(i), "original UV at " + i + " must not move");
            assertEquals(src.positions().get(i), refined.positions().get(i), "original pos at " + i + " must not move");
        }

        // All triangles must be non-degenerate.
        final List<Integer> tris = refined.triangleIndices();
        for (int i = 0; i < tris.size(); i += 3) {
            final int a = tris.get(i);
            final int b = tris.get(i + 1);
            final int c = tris.get(i + 2);
            assertFalse(a == b || b == c || c == a, "triangle has duplicate vertices: " + a + "," + b + "," + c);
            final Point2 pa = refined.uvs().get(a);
            final Point2 pb = refined.uvs().get(b);
            final Point2 pc = refined.uvs().get(c);
            final double cp =
                    (double) (pb.x() - pa.x()) * (pc.y() - pa.y()) - (double) (pb.y() - pa.y()) * (pc.x() - pa.x());
            assertTrue(Math.abs(cp) > 1.0e-9, "triangle is degenerate or zero-area");
        }
    }

    @Test
    void consecutiveRefinementsPreserveManifoldTopologyAndNeverFragment() {
        ArtMeshGeometry current = square();
        final IncrementalMeshRefiner.RefinementOptions options =
                new IncrementalMeshRefiner.RefinementOptions(0.5, 1.5, 0.005, 15);

        // Apply 3 consecutive incremental refinement steps.
        for (int step = 0; step < 3; step++) {
            final IncrementalMeshRefiner.RefinementResult res = IncrementalMeshRefiner.refine(current, null, options);
            assertTrue(res.addedPoints() > 0, "step " + step + " must add points");
            current = res.geometry();

            // Check that every edge is either shared by 2 triangles (interior) or 1 triangle (boundary).
            final List<Integer> tris = current.triangleIndices();
            final java.util.Map<Long, Integer> edgeCounts = new java.util.HashMap<>();
            for (int i = 0; i < tris.size(); i += 3) {
                final int a = tris.get(i);
                final int b = tris.get(i + 1);
                final int c = tris.get(i + 2);
                countEdge(edgeCounts, a, b);
                countEdge(edgeCounts, b, c);
                countEdge(edgeCounts, c, a);
            }
            for (final java.util.Map.Entry<Long, Integer> entry : edgeCounts.entrySet()) {
                assertTrue(
                        entry.getValue() == 1 || entry.getValue() == 2,
                        "manifold edge violation: count=" + entry.getValue() + " on step " + step);
            }
        }
    }

    @Test
    void selectionAwareRefinementOnlyTouchesSelectedTriangles() {
        final ArtMeshGeometry src = grid2x2();
        final int origTriangleCount = src.triangleIndices().size() / 3;

        // Target only triangle 0.
        final Set<Integer> target = Set.of(0);
        final IncrementalMeshRefiner.RefinementOptions options =
                new IncrementalMeshRefiner.RefinementOptions(0.1, 1.1, 0.005, 5);
        final IncrementalMeshRefiner.RefinementResult res = IncrementalMeshRefiner.refine(src, target, options);

        assertTrue(res.addedPoints() > 0);
        final ArtMeshGeometry refined = res.geometry();

        // Triangles 2..7 must still be present as active triangles in the output.
        for (int t = 2; t < origTriangleCount; t++) {
            final int a = src.triangleIndices().get(t * 3);
            final int b = src.triangleIndices().get(t * 3 + 1);
            final int c = src.triangleIndices().get(t * 3 + 2);
            boolean found = false;
            for (int i = 0; i < refined.triangleIndices().size(); i += 3) {
                final int ra = refined.triangleIndices().get(i);
                final int rb = refined.triangleIndices().get(i + 1);
                final int rc = refined.triangleIndices().get(i + 2);
                if (matchesTriangle(a, b, c, ra, rb, rc)) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "unselected triangle " + t + " (" + a + "," + b + "," + c + ") was unexpectedly mutated");
        }
    }

    @Test
    void barycentricMapperPreservesKeyformShearTransformation() {
        final ArtMeshGeometry src = square();
        final IncrementalMeshRefiner.RefinementOptions options =
                new IncrementalMeshRefiner.RefinementOptions(0.5, 1.5, 0.01, 8);
        final IncrementalMeshRefiner.RefinementResult res = IncrementalMeshRefiner.refine(src, null, options);

        // A keyform where positions are sheared: pos.x = uv.x + 0.4 * uv.y, pos.y = uv.y * 1.2
        final List<Point2> keyformPositions = new ArrayList<>();
        for (final Point2 uv : src.uvs()) {
            keyformPositions.add(new Point2(uv.x() + 0.4f * uv.y(), uv.y() * 1.2f));
        }

        final List<Point2> mapped = KeyformBarycentricMapper.mapKeyform(keyformPositions, res.newVertexWeights());
        assertEquals(
                res.geometry().uvs().size(),
                mapped.size(),
                "mapped keyform positions count must match refined vertices");

        // Verify that every newly added vertex strictly conforms to the affine shear field.
        for (int i = res.originalVertexCount(); i < mapped.size(); i++) {
            final Point2 uv = res.geometry().uvs().get(i);
            final Point2 pos = mapped.get(i);
            final float expectedX = uv.x() + 0.4f * uv.y();
            final float expectedY = uv.y() * 1.2f;
            assertEquals(expectedX, pos.x(), 1.0e-4f, "interpolated X must match shear formula");
            assertEquals(expectedY, pos.y(), 1.0e-4f, "interpolated Y must match shear formula");
        }
    }

    private static void countEdge(final java.util.Map<Long, Integer> counts, final int a, final int b) {
        final int lo = Math.min(a, b);
        final int hi = Math.max(a, b);
        final long key = ((long) lo << 32) | (hi & 0xFFFFFFFFL);
        counts.put(key, counts.getOrDefault(key, 0) + 1);
    }

    private static boolean matchesTriangle(
            final int a, final int b, final int c, final int ra, final int rb, final int rc) {
        final Set<Integer> s1 = Set.of(a, b, c);
        final Set<Integer> s2 = Set.of(ra, rb, rc);
        return s1.equals(s2);
    }
}
