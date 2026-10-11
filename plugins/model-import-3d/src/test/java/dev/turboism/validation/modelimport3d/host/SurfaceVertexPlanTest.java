package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class SurfaceVertexPlanTest {
    @Test
    void keepsEveryOriginalCornerAttributeAndTriangleInDeterministicPartitions() {
        var source = SurfaceQuadChartDiscoveryTest.cube(4, 0);
        var input = List.of(source);
        var plan = SurfaceVertexPlan.compile(input, 16);
        verify(plan);
        assertEquals(16, plan.meshes().size());
        assertEquals(plan, SurfaceVertexPlan.compile(input, 16));
        assertEquals(input, plan.sources());
        assertThrows(IllegalArgumentException.class, () -> SurfaceVertexPlan.compile(input, 0));
        assertThrows(IllegalArgumentException.class, () -> SurfaceVertexPlan.compile(input, 1002));
    }

    @Test
    void retainsOriginalDegenerateAndDuplicateFacesWithoutRepair() {
        var old = SurfaceQuadChartDiscoveryTest.cube(1, 0);
        var ix = new ArrayList<>(old.indices());
        ix.addAll(List.of(0, 0, 1));
        ix.addAll(old.indices().subList(0, 3));
        var source = new GlbSurfaceReader.Surface(
                old.node(),
                old.mesh(),
                old.primitive(),
                old.material(),
                old.positions(),
                old.sourceNormals(),
                old.textureUvs(),
                ix,
                old.baseColor(),
                old.doubleSided(),
                old.reflected(),
                old.texture());
        var plan = SurfaceVertexPlan.compile(List.of(source), 4);
        verify(plan);
        assertEquals(
                ix.size() / 3,
                plan.meshes().stream().mapToInt(m -> m.triangles().size()).sum());
    }

    @Test
    void measuresRealCatPartitionWithExactSourceSlotsAndNoSurfaceFitting() throws Exception {
        var input = GlbSurfaceReader.read(Files.readAllBytes(
                Path.of(System.getProperty("mi3d.repo"), "artifacts/cat-textured/source-r2/cat-textured.glb")));
        for (int count : List.of(64, 128, 256, 512)) {
            var plan = SurfaceVertexPlan.compile(input, count);
            verify(plan);
            assertEquals(count, plan.meshes().size());
            assertEquals(
                    70576,
                    plan.meshes().stream().mapToInt(m -> m.triangles().size()).sum());
            assertTrue(plan.repeatedSlots() >= 36530 && plan.repeatedSlots() <= 100000);
            System.out.println("CAT_VERTEX_PARTITION meshes=" + count + " repeatedSlots=" + plan.repeatedSlots()
                    + " cacheSlots841=" + 841 * plan.repeatedSlots());
        }
    }

    @Test
    void refusesUnboundedNonManifoldAdjacencyWithoutDroppingAnyFace() {
        var old = SurfaceQuadChartDiscoveryTest.cube(1, 0);
        var ix = new ArrayList<>(old.indices());
        for (int i = 0; i < 33; i++) ix.addAll(old.indices().subList(0, 3));
        var source = new GlbSurfaceReader.Surface(
                old.node(),
                old.mesh(),
                old.primitive(),
                old.material(),
                old.positions(),
                old.sourceNormals(),
                old.textureUvs(),
                ix,
                old.baseColor(),
                old.doubleSided(),
                old.reflected(),
                old.texture());
        var failure = assertThrows(IllegalArgumentException.class, () -> SurfaceVertexPlan.compile(List.of(source), 4));
        assertTrue(failure.getMessage().contains("adjacency budget"));
    }

    private static void verifyConnected(SurfaceVertexPlan.Mesh mesh) {
        var edges = new HashMap<List<SurfaceProjection.Vertex>, List<Integer>>();
        var order = Comparator.comparingDouble(SurfaceProjection.Vertex::x)
                .thenComparingDouble(SurfaceProjection.Vertex::y)
                .thenComparingDouble(SurfaceProjection.Vertex::z);
        int n = mesh.triangles().size();
        var neighbors = new ArrayList<Set<Integer>>();
        for (int i = 0; i < n; i++) neighbors.add(new HashSet<>());
        for (int t = 0; t < n; t++)
            for (int c = 0; c < 3; c++) {
                var a = mesh.positions().get(mesh.indices().get(3 * t + c));
                var b = mesh.positions().get(mesh.indices().get(3 * t + (c + 1) % 3));
                a = new SurfaceProjection.Vertex(
                        a.x() == 0 ? 0 : a.x(), a.y() == 0 ? 0 : a.y(), a.z() == 0 ? 0 : a.z());
                b = new SurfaceProjection.Vertex(
                        b.x() == 0 ? 0 : b.x(), b.y() == 0 ? 0 : b.y(), b.z() == 0 ? 0 : b.z());
                if (a.equals(b)) continue;
                var key = order.compare(a, b) < 0 ? List.of(a, b) : List.of(b, a);
                edges.computeIfAbsent(key, ignored -> new ArrayList<>()).add(t);
            }
        for (var faces : edges.values())
            for (int a : faces) for (int b : faces) if (a != b) neighbors.get(a).add(b);
        var queue = new ArrayDeque<Integer>();
        var seen = new BitSet(n);
        seen.set(0);
        queue.add(0);
        while (!queue.isEmpty()) {
            int at = queue.remove();
            for (int peer : neighbors.get(at))
                if (!seen.get(peer)) {
                    seen.set(peer);
                    queue.add(peer);
                }
        }
        assertEquals(n, seen.cardinality(), "a draw group must contain connected original faces");
    }

    private static void verify(SurfaceVertexPlan.Plan plan) {
        var seen = new HashSet<SurfaceVertexPlan.Triangle>();
        var slots = new HashSet<SurfaceVertexPlan.Slot>();
        long repeated = 0;
        for (var mesh : plan.meshes()) {
            verifyConnected(mesh);
            repeated += mesh.slots().size();
            assertEquals(mesh.slots().size(), new HashSet<>(mesh.slots()).size());
            assertEquals(mesh.triangles().size() * 3, mesh.indices().size());
            for (int i = 0; i < mesh.slots().size(); i++) {
                var slot = mesh.slots().get(i);
                slots.add(slot);
                var source = plan.sources().get(slot.surface());
                assertEquals(
                        source.positions().get(slot.sourceSlot()),
                        mesh.positions().get(i));
                assertEquals(
                        source.textureUvs().get(slot.sourceSlot()), mesh.uvs().get(i));
            }
            for (int t = 0; t < mesh.triangles().size(); t++) {
                var tri = mesh.triangles().get(t);
                assertTrue(seen.add(tri));
                var source = plan.sources().get(tri.surface());
                for (int c = 0; c < 3; c++)
                    assertEquals(
                            new SurfaceVertexPlan.Slot(
                                    tri.surface(), source.indices().get(3 * tri.sourceTriangle() + c)),
                            mesh.slots().get(mesh.indices().get(3 * t + c)));
            }
        }
        assertEquals(repeated, plan.repeatedSlots());
        assertEquals(
                plan.sources().stream().mapToInt(s -> s.indices().size() / 3).sum(), seen.size());
        assertEquals(plan.sources().stream().mapToInt(s -> s.positions().size()).sum(), slots.size());
    }
}
