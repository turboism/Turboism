package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.model.Point2;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SurfaceSimplifierTest {
    @Test
    void densePlaneReducesWithoutMovingBoundaryOrExceedingCorrespondenceBound() {
        var source = grid(20);
        var r = SurfaceSimplifier.simplify(List.of(source), 1, 1.5, .5);
        assertTrue(r.outputTriangles() < r.sourceTriangles());
        assertTrue(r.maximumGeometryErrorPixels() <= 1.5);
        assertEquals(r, SurfaceSimplifier.simplify(List.of(source), 1, 1.5, .5));
        verify(source, r.surfaces().get(0), r.originalToOutput().get(0), 1.5);
        for (int i = 0; i < source.positions().size(); i++)
            if (i % 20 == 0 || i % 20 == 19 || i < 20 || i >= 380)
                assertEquals(
                        source.positions().get(i),
                        r.surfaces()
                                .get(0)
                                .positions()
                                .get(r.originalToOutput().get(0).get(i)));
    }

    @Test
    void zeroBudgetAndSharedPrimitiveSeamsRemainUnchanged() {
        var s = grid(8);
        assertEquals(
                s, SurfaceSimplifier.simplify(List.of(s), 1, 0, 0).surfaces().get(0));
        var r = SurfaceSimplifier.simplify(List.of(s, s), 1, 100, 100);
        assertEquals(List.of(s, s), r.surfaces());
    }

    @Test
    void tetrahedronCannotCollapseIntoDuplicateTriangles() {
        var s = new GlbSurfaceReader.Surface(
                0,
                0,
                0,
                0,
                List.of(
                        new SurfaceProjection.Vertex(0, 0, 0),
                        new SurfaceProjection.Vertex(1, 0, 0),
                        new SurfaceProjection.Vertex(0, 1, 0),
                        new SurfaceProjection.Vertex(0, 0, 1)),
                List.of(),
                List.of(),
                List.of(0, 2, 1, 0, 1, 3, 0, 3, 2, 1, 2, 3),
                List.of(1d, 1d, 1d, 1d),
                false,
                false);
        assertEquals(
                s,
                SurfaceSimplifier.simplify(List.of(s), 1, 100, 100).surfaces().get(0));
    }

    @Test
    void realCatCandidateRespectsOnePixelBoundAndPreservesTextures() throws Exception {
        var sources = GlbSurfaceReader.read(Files.readAllBytes(
                Path.of(System.getProperty("mi3d.repo"), "artifacts/cat-textured/source-r2/cat-textured.glb")));
        var camera = SurfaceAutoCamera.fit(sources, 512, 512, 400);
        var r = SurfaceSimplifier.simplify(sources, camera.scale(), 1, .5);
        assertTrue(r.maximumGeometryErrorPixels() <= 1);
        assertTrue(r.outputTriangles() <= r.sourceTriangles());
        for (int i = 0; i < sources.size(); i++) {
            verify(sources.get(i), r.surfaces().get(i), r.originalToOutput().get(i), 1 / camera.scale());
            assertEquals(sources.get(i).texture(), r.surfaces().get(i).texture());
            var texture = sources.get(i).texture();
            if (texture != null)
                for (int j = 0; j < r.originalToOutput().get(i).size(); j++) {
                    int slot = r.originalToOutput().get(i).get(j);
                    if (slot < 0) continue;
                    var a = sources.get(i).textureUvs().get(j);
                    var b = r.surfaces().get(i).textureUvs().get(slot);
                    assertTrue(Math.hypot(a.x() - b.x(), a.y() - b.y()) * Math.max(texture.width(), texture.height())
                            <= .5 + 1e-12);
                }
        }
        System.out.println("CAT_SIMPLIFY source=" + r.sourceTriangles() + " output=" + r.outputTriangles()
                + " boundPixels=" + r.maximumGeometryErrorPixels());
    }

    private static void verify(
            GlbSurfaceReader.Surface s, GlbSurfaceReader.Surface out, List<Integer> map, double budget) {
        for (int i = 0; i < map.size(); i++)
            if (map.get(i) >= 0) {
                var a = s.positions().get(i);
                var b = out.positions().get(map.get(i));
                assertTrue(Math.hypot(Math.hypot(a.x() - b.x(), a.y() - b.y()), a.z() - b.z()) <= budget + 1e-12);
            }
        var seen = new HashSet<List<Integer>>();
        for (int i = 0; i < out.indices().size(); i += 3) {
            var t = new ArrayList<>(out.indices().subList(i, i + 3));
            assertEquals(3, new HashSet<>(t).size());
            Collections.sort(t);
            assertTrue(seen.add(t));
        }
    }

    private static GlbSurfaceReader.Surface grid(int n) {
        var p = new ArrayList<SurfaceProjection.Vertex>();
        var uv = new ArrayList<Point2>();
        var idx = new ArrayList<Integer>();
        for (int y = 0; y < n; y++)
            for (int x = 0; x < n; x++) {
                p.add(new SurfaceProjection.Vertex(x, y, 0));
                uv.add(new Point2(x / (float) n, y / (float) n));
            }
        for (int y = 0; y < n - 1; y++)
            for (int x = 0; x < n - 1; x++) {
                int a = y * n + x;
                idx.addAll(List.of(a, a + 1, a + n, a + 1, a + n + 1, a + n));
            }
        return new GlbSurfaceReader.Surface(0, 0, 0, 0, p, List.of(), uv, idx, List.of(1d, 1d, 1d, 1d), false, false);
    }
}
