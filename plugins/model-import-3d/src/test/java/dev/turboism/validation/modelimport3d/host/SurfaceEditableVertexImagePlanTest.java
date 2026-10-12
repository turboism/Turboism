package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class SurfaceEditableVertexImagePlanTest {
    @Test
    void rejectedNativeTopologyIsSplitWithoutChangingMaterialOrSourceCorners() throws Exception {
        var sources = List.of(SurfaceQuadChartDiscoveryTest.cube(1, 0));
        var camera = SurfaceAutoCamera.fit(sources, 512, 512, 400);
        var editable = SurfaceWarpImagePlan.compileEditableVertex(SurfaceVertexPlan.compile(sources, 6), camera, 1001);
        java.util.function.Function<
                        dev.turboism.sdk.cubism.model.ArtMeshGeometry,
                        dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation>
                preview = g -> new dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation(
                g.positions(), g.triangleIndices().size() == 3 ? g.triangleIndices() : List.of());
        var limits = new SurfaceEditableTopologyPlan.Limits(1001, 8192, 100000);
        var refined =
                SurfaceWarpImagePlan.refineEditableVertex(editable.geometry(), editable.images(), limits, preview);
        assertEquals(12, refined.geometry().meshes().size());
        assertEquals(editable.images().imageCount(), refined.images().imageCount());
        for (int i = 0; i < editable.images().imageCount(); i++)
            assertArrayEquals(editable.images().png(i), refined.images().png(i));
        var expected = editable.geometry().meshes().stream()
                .flatMap(m -> m.triangles().stream())
                .toList();
        assertEquals(
                expected,
                refined.geometry().meshes().stream()
                        .flatMap(m -> m.triangles().stream())
                        .toList());
        for (int i = 0; i < refined.geometry().meshes().size(); i++) {
            var mesh = refined.geometry().meshes().get(i);
            var input = refined.images().inputs.get(i);
            assertEquals(1, mesh.triangles().size());
            assertEquals(mesh.indices(), input.imageGeometry().triangleIndices());
            assertEquals(
                    SurfaceProjection.project(mesh.positions(), camera, new SurfaceProjection.Pose(0, 0))
                            .positions(),
                    input.defaultLocal());
            for (int c = 0; c < 3; c++) {
                var slot = mesh.slots().get(c);
                var source = sources.get(slot.surface());
                assertEquals(
                        source.positions().get(slot.sourceSlot()),
                        mesh.positions().get(c));
                assertEquals(
                        source.textureUvs().get(slot.sourceSlot()), mesh.uvs().get(c));
            }
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceWarpImagePlan.refineEditableVertex(
                        editable.geometry(),
                        editable.images(),
                        new SurfaceEditableTopologyPlan.Limits(1, 8192, 100000),
                        preview));
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceWarpImagePlan.refineEditableVertex(
                        editable.geometry(),
                        editable.images(),
                        new SurfaceEditableTopologyPlan.Limits(1001, 1, 100000),
                        preview));
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceWarpImagePlan.refineEditableVertex(
                        editable.geometry(),
                        editable.images(),
                        new SurfaceEditableTopologyPlan.Limits(1001, 8192, 3),
                        preview));
        assertThrows(
                IllegalArgumentException.class,
                () -> SurfaceWarpImagePlan.refineEditableVertex(
                        editable.geometry(),
                        editable.images(),
                        limits,
                        g -> new dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation(g.positions(), List.of())));
    }

    @Test
    void completeCatEditablePlanesKeepEveryOriginalOrientedCornerAndMaterialMapping() throws Exception {
        var sources = GlbSurfaceReader.read(
                ModelImportTestFixtures.readBytes("artifacts/cat-textured/source-r2/cat-textured.glb"));
        var original = SurfaceVertexPlan.compile(sources, 512);
        var camera = SurfaceAutoCamera.fit(sources, 512, 512, 400);
        var editable = SurfaceWarpImagePlan.compileEditableVertex(original, camera, 1001);
        assertEquals(sources, editable.geometry().sources());
        assertTrue(editable.geometry().meshes().size() >= 551
                && editable.geometry().meshes().size() <= 1001);
        assertEquals(15, editable.images().imageCount());
        assertEquals(
                70576,
                editable.geometry().meshes().stream()
                        .mapToInt(m -> m.triangles().size())
                        .sum());
        var faceIds = new HashSet<SurfaceVertexPlan.Triangle>();
        double maximumDerivedError = 0;
        int derived = 0;
        var imageSource =
                ImageIO.read(new ByteArrayInputStream(editable.images().png(0)));
        for (int i = 0; i < editable.geometry().meshes().size(); i++) {
            var mesh = editable.geometry().meshes().get(i);
            var input = editable.images().inputs.get(i);
            var kind = editable.provenance().groups().get(i).plane();
            assertEquals(
                    input.imageGeometry().positions().size(),
                    new HashSet<>(input.imageGeometry().positions()).size(),
                    "editable image points must not alias distinct source slots");
            assertEquals(
                    SurfaceProjection.project(mesh.positions(), camera, new SurfaceProjection.Pose(0, 0))
                            .positions(),
                    input.defaultLocal());
            for (int face = 0; face < mesh.triangles().size(); face++) {
                var identity = mesh.triangles().get(face);
                assertTrue(faceIds.add(identity));
                var source = sources.get(identity.surface());
                for (int c = 0; c < 3; c++) {
                    int sourceSlot = source.indices().get(3 * identity.sourceTriangle() + c);
                    int slot = mesh.indices().get(3 * face + c);
                    assertEquals(
                            new SurfaceVertexPlan.Slot(identity.surface(), sourceSlot),
                            mesh.slots().get(slot));
                    assertEquals(
                            source.positions().get(sourceSlot), mesh.positions().get(slot));
                    assertEquals(source.textureUvs().get(sourceSlot), mesh.uvs().get(slot));
                }
            }
            if (kind == SurfaceNativeSourceWindingPlan.Plane.NEEDS_DERIVED_PLANE) {
                derived++;
                assertEquals(List.of(0, 1, 2), mesh.indices());
                assertEquals(mesh.indices(), input.imageGeometry().triangleIndices());
                var image = ImageIO.read(new ByteArrayInputStream(
                        editable.images().png(editable.images().meshImages.get(i))));
                for (double v : new double[] {.125, .25, .5, .75})
                    for (double w : new double[] {.125, .25, .5, .75})
                        if (v + w < 1) {
                            double u = 1 - v - w;
                            var points = input.imageGeometry().positions();
                            double x = u * points.get(0).x()
                                    + v * points.get(1).x()
                                    + w * points.get(2).x();
                            double y = u * points.get(0).y()
                                    + v * points.get(1).y()
                                    + w * points.get(2).y();
                            double sx = (u * mesh.uvs().get(0).x()
                                            + v * mesh.uvs().get(1).x()
                                            + w * mesh.uvs().get(2).x())
                                    * imageSource.getWidth();
                            double sy = (u * mesh.uvs().get(0).y()
                                            + v * mesh.uvs().get(1).y()
                                            + w * mesh.uvs().get(2).y())
                                    * imageSource.getHeight();
                            for (int channel = 0; channel < 4; channel++)
                                maximumDerivedError = Math.max(
                                        maximumDerivedError,
                                        Math.abs(sample(image, x, y, 24 - channel * 8)
                                                - sample(imageSource, sx, sy, 24 - channel * 8)));
                        }
                image.flush();
            }
        }
        imageSource.flush();
        assertEquals(13, derived);
        assertEquals(70576, faceIds.size());
        assertTrue(
                maximumDerivedError <= 1, "derived source-UV material error in 8-bit channels: " + maximumDerivedError);
        System.out.println(
                "CAT_EDITABLE_SOURCE meshes=" + editable.geometry().meshes().size() + " slots="
                        + editable.geometry().repeatedSlots() + " faces=" + faceIds.size() + " images="
                        + editable.images().imageCount() + " derivedFaces=" + derived + " maxSampleChannelError="
                        + maximumDerivedError);
    }

    private static double sample(BufferedImage image, double x, double y, int shift) {
        x -= .5;
        y -= .5;
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y);
        double dx = x - ix, dy = y - iy;
        double a = at(image, ix, iy, shift),
                b = at(image, ix + 1, iy, shift),
                c = at(image, ix, iy + 1, shift),
                d = at(image, ix + 1, iy + 1, shift);
        return (a * (1 - dx) + b * dx) * (1 - dy) + (c * (1 - dx) + d * dx) * dy;
    }

    private static double at(BufferedImage image, int x, int y, int shift) {
        return (image.getRGB(
                                Math.max(0, Math.min(image.getWidth() - 1, x)),
                                Math.max(0, Math.min(image.getHeight() - 1, y)))
                        >>> shift)
                & 255;
    }
}
