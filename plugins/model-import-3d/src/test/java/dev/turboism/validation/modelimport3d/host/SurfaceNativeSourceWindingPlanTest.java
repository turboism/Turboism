package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.model.Point2;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SurfaceNativeSourceWindingPlanTest {
    @Test
    void separatesCoincidentUvsWithoutWeldingDistinctSourceVertices() {
        var mesh = new SurfaceVertexPlan.Mesh(
                List.of(
                        new SurfaceProjection.Vertex(0, 0, 0),
                        new SurfaceProjection.Vertex(1, 0, 0),
                        new SurfaceProjection.Vertex(0, 1, 0),
                        new SurfaceProjection.Vertex(0, 0, 1),
                        new SurfaceProjection.Vertex(1, 0, 1),
                        new SurfaceProjection.Vertex(0, 1, 1)),
                List.of(
                        new Point2(0, 0),
                        new Point2(1, 0),
                        new Point2(0, 1),
                        new Point2(0, 0),
                        new Point2(1, 0),
                        new Point2(0, 1)),
                List.of(
                        new SurfaceVertexPlan.Slot(0, 0),
                        new SurfaceVertexPlan.Slot(0, 1),
                        new SurfaceVertexPlan.Slot(0, 2),
                        new SurfaceVertexPlan.Slot(0, 3),
                        new SurfaceVertexPlan.Slot(0, 4),
                        new SurfaceVertexPlan.Slot(0, 5)),
                List.of(0, 1, 2, 3, 4, 5),
                List.of(new SurfaceVertexPlan.Triangle(0, 0), new SurfaceVertexPlan.Triangle(0, 1)));
        var original = new SurfaceVertexPlan.Plan(List.of(), List.of(mesh), 6);
        var result = SurfaceNativeSourceWindingPlan.compile(original, 2);
        assertEquals(2, result.groups().size());
        verify(original, result);
        for (var group : result.groups())
            assertEquals(group.mesh().uvs().size(), new HashSet<>(group.mesh().uvs()).size());
        assertThrows(IllegalArgumentException.class, () -> SurfaceNativeSourceWindingPlan.compile(original, 1));
    }

    @Test
    void separatesOppositeAndZeroUvWindingWithoutLosingDuplicateFacesOrCorners() {
        var mesh = new SurfaceVertexPlan.Mesh(
                List.of(
                        new SurfaceProjection.Vertex(0, 0, 0),
                        new SurfaceProjection.Vertex(1, 0, 0),
                        new SurfaceProjection.Vertex(1, 1, 0),
                        new SurfaceProjection.Vertex(0, 1, 0)),
                List.of(new Point2(0, 0), new Point2(1, 0), new Point2(1, 1), new Point2(0, 1)),
                List.of(
                        new SurfaceVertexPlan.Slot(0, 0),
                        new SurfaceVertexPlan.Slot(0, 1),
                        new SurfaceVertexPlan.Slot(0, 2),
                        new SurfaceVertexPlan.Slot(0, 3)),
                List.of(0, 1, 2, 0, 3, 2, 0, 0, 1, 0, 1, 2),
                List.of(
                        new SurfaceVertexPlan.Triangle(0, 0),
                        new SurfaceVertexPlan.Triangle(0, 1),
                        new SurfaceVertexPlan.Triangle(0, 2),
                        new SurfaceVertexPlan.Triangle(0, 3)));
        var original = new SurfaceVertexPlan.Plan(List.of(), List.of(mesh), 4);
        var result = SurfaceNativeSourceWindingPlan.compile(original, 3);
        assertEquals(3, result.groups().size());
        assertTrue(result.requiresDerivedPlanes());
        assertEquals(
                List.of(
                        SurfaceNativeSourceWindingPlan.Plane.ORIGINAL_IMAGE,
                        SurfaceNativeSourceWindingPlan.Plane.REFLECTED_IMAGE,
                        SurfaceNativeSourceWindingPlan.Plane.NEEDS_DERIVED_PLANE),
                result.groups().stream()
                        .map(SurfaceNativeSourceWindingPlan.Group::plane)
                        .toList());
        assertEquals(2, result.groups().get(0).mesh().triangles().size());
        verify(original, result);
        assertThrows(IllegalArgumentException.class, () -> SurfaceNativeSourceWindingPlan.compile(original, 2));
    }

    @Test
    void allOriginalCatFacesAndUvCornersSurviveEditablePlanePlanning() throws Exception {
        var sources = GlbSurfaceReader.read(Files.readAllBytes(
                Path.of(System.getProperty("mi3d.repo"), "artifacts/cat-textured/source-r2/cat-textured.glb")));
        var original = SurfaceVertexPlan.compile(sources, 512);
        var result = SurfaceNativeSourceWindingPlan.compile(original, 1001);
        verify(original, result);
        assertEquals(
                70576,
                result.groups().stream()
                        .mapToInt(g -> g.mesh().triangles().size())
                        .sum());
        assertTrue(result.requiresDerivedPlanes(), "zero UV faces must remain explicit, not silently become editable");
        assertTrue(result.repeatedSlots() >= original.repeatedSlots());
        for (var plane : SurfaceNativeSourceWindingPlan.Plane.values())
            System.out.println("CAT_EDITABLE_WINDING plane=" + plane + " groups="
                    + result.groups().stream().filter(g -> g.plane() == plane).count() + " faces="
                    + result.groups().stream()
                            .filter(g -> g.plane() == plane)
                            .mapToInt(g -> g.mesh().triangles().size())
                            .sum());
        System.out.println("CAT_EDITABLE_WINDING slots=" + result.repeatedSlots() + " sourceMeshes="
                + result.groups().size());
    }

    private static void verify(SurfaceVertexPlan.Plan original, SurfaceNativeSourceWindingPlan.Plan actual) {
        assertEquals(original.sources(), actual.sources());
        var seen = new HashSet<SurfaceVertexPlan.Triangle>();
        long slots = 0;
        for (var group : actual.groups()) {
            var before = original.meshes().get(group.originalPartition());
            var after = group.mesh();
            slots += after.slots().size();
            if (group.plane() != SurfaceNativeSourceWindingPlan.Plane.NEEDS_DERIVED_PLANE)
                assertEquals(after.slots().size(), new HashSet<>(after.slots()).size());
            for (int i = 0; i < after.slots().size(); i++) {
                int old = before.slots().indexOf(after.slots().get(i));
                assertTrue(old >= 0);
                assertEquals(before.positions().get(old), after.positions().get(i));
                assertEquals(before.uvs().get(old), after.uvs().get(i));
            }
            for (int face = 0; face < after.triangles().size(); face++) {
                var id = after.triangles().get(face);
                assertTrue(seen.add(id));
                int old = before.triangles().indexOf(id);
                assertTrue(old >= 0);
                for (int c = 0; c < 3; c++)
                    assertEquals(
                            before.slots().get(before.indices().get(3 * old + c)),
                            after.slots().get(after.indices().get(3 * face + c)),
                            "original oriented corner identity");
            }
        }
        assertEquals(
                original.meshes().stream().mapToInt(m -> m.triangles().size()).sum(), seen.size());
        assertEquals(actual.repeatedSlots(), slots);
    }
}
