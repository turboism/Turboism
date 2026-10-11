package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.json.Json;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.Test;

class SurfacePatchCollapseProofTest {
    @Test
    void planarFanHasZeroSurfaceErrorDespiteLargeVertexMotion() {
        var s = fan(0, 0);
        var proof = SurfacePatchCollapseProof.compare(s, before(), after()).orElseThrow();
        assertTrue(proof.geometry() < 1e-8);
        assertTrue(proof.uv() < 1e-8);
        var result = SurfaceSimplifier.simplifySurface(List.of(grid(24)), 1, .001, .5);
        assertTrue(
                result.outputTriangles() < result.sourceTriangles() / 2,
                "must eliminate redundant coplanar tessellation");
        assertTrue(result.maximumGeometryErrorPixels() <= .001);
        System.out.println(
                "PLANE_SURFACE_SIMPLIFY source=" + result.sourceTriangles() + " output=" + result.outputTriangles());
    }

    @Test
    void raisedVertexAndNonAffineUvAreMeasuredRatherThanSilentlyFlattened() {
        var height =
                SurfacePatchCollapseProof.compare(fan(.2, 0), before(), after()).orElseThrow();
        assertEquals(.2, height.geometry(), 1e-8);
        var uv =
                SurfacePatchCollapseProof.compare(fan(0, .1), before(), after()).orElseThrow();
        assertEquals(.1, uv.uv(), 1e-6);
    }

    @Test
    void changedBoundaryAndFoldedProjectionAreRejected() {
        assertTrue(SurfacePatchCollapseProof.compare(fan(0, 0), before(), List.of(new int[] {0, 1, 2}))
                .isEmpty());
        assertTrue(SurfacePatchCollapseProof.compare(
                        fan(0, 0), before(), List.of(new int[] {0, 2, 1}, new int[] {0, 2, 3}))
                .isEmpty());
    }

    @Test
    void rigidRotationPreservesTheSurfaceComparisonAndSkinnyDomainsRefuse() {
        var s = fan(.2, .1);
        var rotated = s.positions().stream()
                .map(p -> {
                    double x = (p.x() - p.z()) / Math.sqrt(2), z = (p.x() + p.z()) / Math.sqrt(2);
                    return new SurfaceProjection.Vertex(x, (p.y() - z) / Math.sqrt(2), (p.y() + z) / Math.sqrt(2));
                })
                .toList();
        var r = new GlbSurfaceReader.Surface(
                0, 0, 0, 0, rotated, List.of(), s.textureUvs(), s.indices(), s.baseColor(), false, false);
        var proof = SurfacePatchCollapseProof.compare(r, before(), after()).orElseThrow();
        assertEquals(.2, proof.geometry(), 1e-8);
        assertEquals(.1, proof.uv(), 1e-6);
        var skinny = s.positions().stream()
                .map(p -> new SurfaceProjection.Vertex(p.x(), p.y() * 1e-8, 0))
                .toList();
        var t = new GlbSurfaceReader.Surface(
                0, 0, 0, 0, skinny, List.of(), s.textureUvs(), s.indices(), s.baseColor(), false, false);
        assertTrue(SurfacePatchCollapseProof.compare(t, before(), after()).isEmpty());
    }

    @Test
    void realCatSurfaceCandidateMeasuresReductionWithinTheReservedBudget() throws Exception {
        var sources = GlbSurfaceReader.read(Files.readAllBytes(
                Path.of(System.getProperty("mi3d.repo"), "artifacts/cat-textured/source-r2/cat-textured.glb")));
        var camera = SurfaceAutoCamera.fit(sources, 512, 512, 400);
        double budget = 5 - (800 * (1 - Math.cos(Math.toRadians(45d / 4 / 2))) + .01);
        var r = SurfaceSimplifier.simplifySurface(sources, camera.scale(), budget, .5);
        assertTrue(r.outputTriangles() < r.sourceTriangles());
        assertTrue(r.maximumGeometryErrorPixels() <= budget);
        assertTrue(r.maximumTextureErrorTexels() <= .5);
        for (int i = 0; i < sources.size(); i++)
            assertEquals(sources.get(i).texture(), r.surfaces().get(i).texture());
        var geometry = SurfaceVertexPlan.compile(r.surfaces(), 512);
        var root = Path.of(System.getProperty("mi3d.repo"));
        var output = root.resolve("build/model-import-3d-offline/cat-surface-optimization-r2-20261003");
        Files.createDirectories(output);
        var meshes = r.surfaces().stream()
                .map(s -> Map.of(
                        "positions",
                        s.positions().stream()
                                .map(p -> List.of(p.x(), p.y(), p.z()))
                                .toList(),
                        "uvs",
                        s.textureUvs().stream().map(p -> List.of(p.x(), p.y())).toList(),
                        "indices",
                        s.indices()))
                .toList();
        var source = root.resolve("artifacts/cat-textured/source-r2/cat-textured.glb");
        Files.write(
                output.resolve("optimized-geometry.json"),
                Json.bytes(Map.of(
                        "sourceSha256",
                        HexFormat.of()
                                .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(source))),
                        "sourceTriangles",
                        r.sourceTriangles(),
                        "outputTriangles",
                        r.outputTriangles(),
                        "scale",
                        camera.scale(),
                        "geometryBoundPixels",
                        r.maximumGeometryErrorPixels(),
                        "textureBudgetTexels",
                        .5,
                        "maximumTextureErrorTexels",
                        r.maximumTextureErrorTexels(),
                        "partitionSlots",
                        geometry.repeatedSlots(),
                        "surfaces",
                        meshes)));
        System.out.println("CAT_SURFACE_SIMPLIFY source=" + r.sourceTriangles() + " output=" + r.outputTriangles()
                + " geometryBoundPixels=" + r.maximumGeometryErrorPixels() + " textureBoundTexels="
                + r.maximumTextureErrorTexels() + " partitionSlots=" + geometry.repeatedSlots());
    }

    private static List<int[]> before() {
        return List.of(new int[] {0, 1, 4}, new int[] {1, 2, 4}, new int[] {2, 3, 4}, new int[] {3, 0, 4});
    }

    private static List<int[]> after() {
        return List.of(new int[] {0, 1, 2}, new int[] {0, 2, 3});
    }

    private static GlbSurfaceReader.Surface fan(double height, double uvError) {
        return new GlbSurfaceReader.Surface(
                0,
                0,
                0,
                0,
                List.of(
                        new SurfaceProjection.Vertex(0, 0, 0),
                        new SurfaceProjection.Vertex(1, 0, 0),
                        new SurfaceProjection.Vertex(1, 1, 0),
                        new SurfaceProjection.Vertex(0, 1, 0),
                        new SurfaceProjection.Vertex(.5, .5, height)),
                List.of(),
                List.of(
                        new Point2(0, 0),
                        new Point2(1, 0),
                        new Point2(1, 1),
                        new Point2(0, 1),
                        new Point2((float) (.5 + uvError), .5f)),
                List.of(0, 1, 4, 1, 2, 4, 2, 3, 4, 3, 0, 4),
                List.of(1d, 1d, 1d, 1d),
                false,
                false);
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
