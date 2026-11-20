package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Differential correctness, bytecode composition, and concurrency tests for
 * {@link TriangulationSpatialIndex} and {@link TriangulationSpatialIndexTransformer}.
 */
final class TriangulationSpatialIndexDifferentialTest {

    public static final class Pt {
        private final float x;
        private final float y;
        private final int index;

        public Pt(final float x, final float y, final int index) {
            this.x = x;
            this.y = y;
            this.index = index;
        }

        public float getX() {
            return x;
        }

        public float getY() {
            return y;
        }

        public int getIndex() {
            return index;
        }
    }

    public static final class Tri {
        private final Pt a;
        private final Pt b;
        private final Pt c;

        public Tri(final Pt a, final Pt b, final Pt c) {
            this.a = a;
            this.b = b;
            this.c = c;
        }

        public Pt a() {
            return a;
        }

        public Pt b() {
            return b;
        }

        public Pt c() {
            return c;
        }
    }

    public static final class Edge {
        private final Pt a;
        private final Pt b;

        public Edge(final Pt a, final Pt b) {
            this.a = a;
            this.b = b;
        }

        public Pt a() {
            return a;
        }

        public Pt b() {
            return b;
        }
    }

    public static final class MockTriangleList implements Iterable<Tri> {
        final LinkedHashSet<Tri> set = new LinkedHashSet<>();

        public int a() {
            return set.size();
        }

        public boolean add(final Tri tri) {
            final boolean changed = set.add(tri);
            if (changed) {
                TriangulationSpatialIndex.onAdd(
                        this,
                        set,
                        tri,
                        tri.a,
                        tri.a.x,
                        tri.a.y,
                        tri.a.index,
                        tri.b,
                        tri.b.x,
                        tri.b.y,
                        tri.b.index,
                        tri.c,
                        tri.c.x,
                        tri.c.y,
                        tri.c.index);
            }
            return changed;
        }

        public boolean remove(final Tri tri) {
            final boolean changed = set.remove(tri);
            if (changed) {
                TriangulationSpatialIndex.onRemove(this, set, tri);
            }
            return changed;
        }

        public void clear() {
            set.clear();
            TriangulationSpatialIndex.onClear(this, set);
        }

        @Override
        public Iterator<Tri> iterator() {
            return set.iterator();
        }
    }

    public static final class MockEdgeSet {
        private final ArrayList<Edge> a = new ArrayList<>();

        public ArrayList<Edge> a() {
            return a;
        }

        public boolean add(final Edge edge) {
            final boolean changed = a.add(edge);
            if (changed) {
                TriangulationSpatialIndex.onEdgeAdd(
                        this, a, edge, edge.a.index, edge.a.x, edge.a.y, edge.b.index, edge.b.x, edge.b.y);
            }
            return changed;
        }

        public boolean remove(final Edge edge) {
            final boolean changed = a.remove(edge);
            if (changed) {
                TriangulationSpatialIndex.onEdgeMutate(this, a);
            }
            return changed;
        }
    }

    @Test
    void pointLocationMatchesNativeInsertionOrderUnderTiesSupertrianglesAndNegativeZero() {
        final MockTriangleList list = new MockTriangleList();
        final Random rng = new Random(0x5EED1234L);

        // Outlier supertriangles covering [-1000, 1000] should land in oversized without breaking dense grid.
        final Pt sup0 = new Pt(-1000f, -1000f, 10_000);
        final Pt sup1 = new Pt(1000f, -1000f, 10_001);
        final Pt sup2 = new Pt(0f, 1000f, 10_002);
        list.add(new Tri(sup0, sup1, sup2));

        // Shared-edge tie pair with +/-0.0f coordinates: earliest inserted must win.
        final Pt pZeroPos = new Pt(+0.0f, +0.0f, 1);
        final Pt pZeroNeg = new Pt(-0.0f, 1.0f, 2);
        final Pt pLeft = new Pt(-1.0f, 0.5f, 3);
        final Pt pRight = new Pt(1.0f, 0.5f, 4);
        final Tri firstShared = new Tri(pZeroPos, pZeroNeg, pLeft);
        final Tri secondShared = new Tri(pZeroPos, pZeroNeg, pRight);
        list.add(firstShared);
        list.add(secondShared);

        final List<Pt> points = new ArrayList<>();
        points.add(pZeroPos);
        points.add(pZeroNeg);
        points.add(pLeft);
        points.add(pRight);

        // Add clustered grid + jittered triangles above grid threshold with interleaved removals.
        int nextIdx = 5;
        for (int i = 0; i < 120; i++) {
            final float bx = (i % 12) * 2.0f + rng.nextFloat() * 0.25f;
            final float by = (i / 12) * 2.0f + rng.nextFloat() * 0.25f;
            final Pt a = new Pt(bx, by, nextIdx++);
            final Pt b = new Pt(bx + 1.8f, by + 0.1f, nextIdx++);
            final Pt c = new Pt(bx + 0.5f, by + 1.9f, nextIdx++);
            points.add(a);
            points.add(b);
            points.add(c);
            final Tri t = new Tri(a, b, c);
            list.add(t);
            if (i % 7 == 3) {
                list.remove(t);
            }
        }

        // Probe exact shared boundary edge, supertriangle region, own-vertex index skip, and random points.
        final List<Pt> probes = new ArrayList<>(points);
        probes.add(new Pt(-0.0f, 0.5f, 99_999)); // shared vertical edge -> firstShared must win
        probes.add(new Pt(-0.0f, 0.5f, 1)); // shares vertex index 1 with both -> supertriangle must win
        probes.add(new Pt(500.0f, 100.0f, 99_998)); // only supertriangle covers this
        probes.add(new Pt(9999.0f, 9999.0f, 99_997)); // outside everything -> null
        for (int i = 0; i < 150; i++) {
            probes.add(new Pt(-2f + rng.nextFloat() * 28f, -2f + rng.nextFloat() * 24f, i % 40));
        }

        for (final Pt q : probes) {
            final Tri expected = brutePointLocate(list, q);
            final Object actual = TriangulationSpatialIndex.findContainingTriangle(list, q.x, q.y, q.index);
            assertNotSame(TriangulationSpatialIndex.FALLBACK, actual);
            assertSame(expected, actual, "point (" + q.x + "," + q.y + ") idx=" + q.index);
        }

        // Iterator removal bypasses onRemove: spatial index must self-heal via size mismatch rebuild.
        final Iterator<Tri> it = list.set.iterator();
        it.next();
        it.next();
        it.remove();
        for (final Pt q : probes.subList(0, 40)) {
            assertSame(
                    brutePointLocate(list, q),
                    TriangulationSpatialIndex.findContainingTriangle(list, q.x, q.y, q.index));
        }
    }

    @Test
    void intersectingEdgesPreserveOrderDuplicatesAndPostRemovalDenseSequence() {
        final MockTriangleList tris = new MockTriangleList();
        final MockEdgeSet edges = new MockEdgeSet();
        final Random rng = new Random(0xC0FFEEL);

        final List<Pt> pts = new ArrayList<>();
        for (int i = 0; i < 80; i++) {
            pts.add(new Pt(rng.nextFloat() * 100f, rng.nextFloat() * 100f, i));
        }
        final List<Tri> created = new ArrayList<>();
        for (int i = 0; i < 90; i++) {
            final Pt a = pts.get(i % pts.size());
            final Pt b = pts.get((i * 7 + 3) % pts.size());
            final Pt c = pts.get((i * 13 + 5) % pts.size());
            final Tri t = new Tri(a, b, c);
            tris.add(t);
            created.add(t);
        }
        // Remove every 5th triangle so dense[] swaps order while LinkedHashSet keeps seq order.
        for (int i = 4; i < created.size(); i += 5) {
            tris.remove(created.get(i));
        }
        // Add long diagonal + short constraint edges including shared endpoints.
        for (int i = 0; i < 25; i++) {
            final Pt p0 = pts.get((i * 3) % pts.size());
            final Pt p1 = pts.get((i * 11 + 17) % pts.size());
            edges.add(new Edge(p0, p1));
        }
        // Also test < MIN_TRIANGLES_FOR_GRID path where gridBuilt is false after removals.
        assertEdgeIntersectionsMatchBruteForce(tris, edges);

        final MockTriangleList smallTris = new MockTriangleList();
        for (int i = 0; i < 12; i++) {
            smallTris.add(created.get(i * 2));
        }
        smallTris.remove(created.get(0));
        smallTris.remove(created.get(4));
        assertEdgeIntersectionsMatchBruteForce(smallTris, edges);
    }

    @Test
    void edgeHashAndCollinearCandidatesCoverForwardBackwardWrappedAnglesAndMutationFallback() {
        final MockEdgeSet edges = new MockEdgeSet();
        final Pt z0 = new Pt(0.0f, 0.0f, 0);
        final Pt z1 = new Pt(10.0f, 0.0f, 1);
        final Pt pA = new Pt(5.0f, 0.0f, 2); // strictly collinear inside z0->z1
        final Pt pBack = new Pt(-4.0f, -1.0e-7f, 3); // dot < 0, cross < 0 -> native atan2 + PI <= 1e-6f
        final Pt pOff = new Pt(5.0f, 0.5f, 4);

        edges.add(new Edge(z0, z0)); // (0,0) sentinel edge
        edges.add(new Edge(z0, z1));

        // Add enough grid edges to trigger 2D edge spatial grid.
        for (int i = 10; i < 50; i++) {
            edges.add(new Edge(new Pt(i, i, i), new Pt(i + 2.0f, i + 1.0f, i + 100)));
        }

        // Directed & undirected O(1) containsEdge including (0,0) and reversed orientation.
        assertEquals(1, TriangulationSpatialIndex.containsEdge(edges, edges.a(), 0, 0, true));
        assertEquals(1, TriangulationSpatialIndex.containsEdge(edges, edges.a(), 0, 1, true));
        assertEquals(0, TriangulationSpatialIndex.containsEdge(edges, edges.a(), 1, 0, true));
        assertEquals(1, TriangulationSpatialIndex.containsEdge(edges, edges.a(), 1, 0, false));
        assertEquals(0, TriangulationSpatialIndex.containsEdge(edges, edges.a(), 77, 88, false));

        // Unhooked direct ArrayList mutation shrinks size -> self-heals on next check.
        edges.a().remove(0);
        assertEquals(0, TriangulationSpatialIndex.containsEdge(edges, edges.a(), 0, 0, true));
        assertEquals(1, TriangulationSpatialIndex.containsEdge(edges, edges.a(), 0, 1, true));

        // Soundness of collinear candidate search on interior collinear AND -180deg wrapped case.
        assertCollinearSoundness(edges, pA.x, pA.y, pA.index);
        assertCollinearSoundness(edges, pBack.x, pBack.y, pBack.index);
        // Non-collinear interior point must reject cleanly with null (zero allocations).
        assertNull(TriangulationSpatialIndex.findCollinearCandidates(edges, pOff.x, pOff.y, pOff.index));
    }

    @Test
    void contourPruningMatchesNativeParityIncludingHolesIslandsAndLargeSlabs() {
        final List<Pt> points = new ArrayList<>();
        // Contour 0: large 80-vertex concave L-shape/star ring (triggers Y-slab path >= 64 edges).
        final int outerVerts = 80;
        final int[] outer = new int[outerVerts];
        for (int i = 0; i < outerVerts; i++) {
            final double angle = (2.0 * Math.PI * i) / outerVerts;
            final float r = (i % 4 == 0) ? 12.0f : 6.0f;
            points.add(new Pt((float) (Math.cos(angle) * r), (float) (Math.sin(angle) * r), i));
            outer[i] = i;
        }
        // Contour 1: small separated island at x=[25..30], y=[-2..2]
        final int[] island = new int[4];
        final int base = points.size();
        points.add(new Pt(25f, -2f, base));
        points.add(new Pt(30f, -2f, base + 1));
        points.add(new Pt(30f, 2f, base + 2));
        points.add(new Pt(25f, 2f, base + 3));
        for (int i = 0; i < 4; i++) {
            island[i] = base + i;
        }

        final List<int[]> contours = List.of(outer, island);
        final List<Tri> nativeTris = new ArrayList<>();
        final List<Tri> fastTris = new ArrayList<>();
        final Random rng = new Random(0xABCDL);
        for (int i = 0; i < 240; i++) {
            final float cx = -15f + rng.nextFloat() * 50f;
            final float cy = -15f + rng.nextFloat() * 30f;
            final Tri t = new Tri(
                    new Pt(cx - 0.3f, cy - 0.2f, i * 3),
                    new Pt(cx + 0.4f, cy - 0.1f, i * 3 + 1),
                    new Pt(cx, cy + 0.3f, i * 3 + 2));
            nativeTris.add(t);
            fastTris.add(t);
        }

        final Iterator<Tri> nit = nativeTris.iterator();
        while (nit.hasNext()) {
            final Tri t = nit.next();
            final float gx = (t.a.x + t.b.x + t.c.x) / 3.0f;
            final float gy = (t.a.y + t.b.y + t.c.y) / 3.0f;
            boolean inside = false;
            for (final int[] c : contours) {
                if (nativePointInContour(points, c, gx, gy)) {
                    inside = true;
                    break;
                }
            }
            if (!inside) {
                nit.remove();
            }
        }

        assertTrue(TriangulationSpatialIndex.pruneOutsideContours(points, contours, fastTris));
        assertEquals(nativeTris, fastTris, "contour pruning must leave identical triangles in exact order");
    }

    @Test
    void transformerIsFailClosedOnUnrelatedAndMalformedClasses() {
        final TriangulationSpatialIndexTransformer transformer = new TriangulationSpatialIndexTransformer();
        assertNull(transformer.transform(null, "com/example/Other", null, null, new byte[] {1, 2, 3}));
        assertEquals(TriangulationSpatialIndexTransformer.Outcome.NONE, transformer.outcome());
        assertNull(transformer.transform(
                null, TriangulationSpatialIndexTransformer.TARGET_LIST_INTERNAL_NAME, null, null, null));

        final ClassWriter malformed = new ClassWriter(0);
        malformed.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                TriangulationSpatialIndexTransformer.TARGET_LIST_INTERNAL_NAME,
                null,
                "java/lang/Object",
                null);
        malformed.visitEnd();
        for (final String target : List.of(
                TriangulationSpatialIndexTransformer.TARGET_LIST_INTERNAL_NAME,
                TriangulationSpatialIndexTransformer.TARGET_EDGE_SET_INTERNAL_NAME,
                TriangulationSpatialIndexTransformer.TARGET_SOLVER_INTERNAL_NAME,
                TriangulationSpatialIndexTransformer.TARGET_BASE_SOLVER_INTERNAL_NAME)) {
            assertNull(transformer.transform(null, target, null, null, malformed.toByteArray()));
            assertEquals(TriangulationSpatialIndexTransformer.Outcome.SHAPE_REJECTED, transformer.outcome());
        }
    }

    @Test
    void concurrentWorkersMaintainIndependentStateAcrossAllThreads() throws Exception {
        final int workers = 12;
        final ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            final List<Callable<Void>> tasks = new ArrayList<>();
            for (int w = 0; w < workers; w++) {
                final int workerSeed = w;
                tasks.add(() -> {
                    for (int iter = 0; iter < 15; iter++) {
                        final Random rng = new Random(workerSeed * 1009L + iter);
                        final MockTriangleList list = new MockTriangleList();
                        final MockEdgeSet edges = new MockEdgeSet();
                        final List<Pt> pts = new ArrayList<>();
                        for (int i = 0; i < 60; i++) {
                            pts.add(new Pt(rng.nextFloat() * 50f, rng.nextFloat() * 50f, i));
                        }
                        for (int i = 0; i < 60; i++) {
                            list.add(new Tri(pts.get(i), pts.get((i + 1) % 60), pts.get((i + 7) % 60)));
                            if (i % 2 == 0) {
                                edges.add(new Edge(pts.get(i), pts.get((i + 3) % 60)));
                            }
                        }
                        for (int i = 0; i < 20; i++) {
                            final Pt q = new Pt(rng.nextFloat() * 50f, rng.nextFloat() * 50f, 1000 + i);
                            assertSame(
                                    brutePointLocate(list, q),
                                    TriangulationSpatialIndex.findContainingTriangle(list, q.x, q.y, q.index));
                        }
                        assertEdgeIntersectionsMatchBruteForce(list, edges);
                        assertEquals(1, TriangulationSpatialIndex.containsEdge(edges, edges.a(), 0, 3, true));
                    }
                    return null;
                });
            }
            for (final Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void transformsAndExecutesWovenEdgeSetInChildClassLoader() throws Exception {
        final byte[] patchedK = new TriangulationSpatialIndexTransformer()
                .transform(
                        null,
                        TriangulationSpatialIndexTransformer.TARGET_EDGE_SET_INTERNAL_NAME,
                        null,
                        null,
                        minimalEdgeSetHostBytes());
        assertNotNull(patchedK);

        final var loader = new ClassLoader(TriangulationSpatialIndexDifferentialTest.class.getClassLoader()) {
            @Override
            protected Class<?> findClass(final String name) throws ClassNotFoundException {
                final String internal = name.replace('.', '/');
                if (TriangulationSpatialIndexTransformer.TARGET_EDGE_SET_INTERNAL_NAME.equals(internal)) {
                    return defineClass(name, patchedK, 0, patchedK.length);
                }
                if (internal.equals("com/live2d/graphics3d/editableMesh/triangulation/TriPoint")) {
                    final byte[] b = minimalPointBytes();
                    return defineClass(name, b, 0, b.length);
                }
                if (internal.equals("com/live2d/graphics3d/editableMesh/triangulation/j")) {
                    final byte[] b = minimalEdgeBytes();
                    return defineClass(name, b, 0, b.length);
                }
                if (internal.equals("kotlin/jvm/internal/Intrinsics")) {
                    final byte[] b = minimalIntrinsicsBytes();
                    return defineClass(name, b, 0, b.length);
                }
                return super.findClass(name);
            }
        };
        final Class<?> ptCls = loader.loadClass("com.live2d.graphics3d.editableMesh.triangulation.TriPoint");
        final Class<?> edgeCls = loader.loadClass("com.live2d.graphics3d.editableMesh.triangulation.j");
        final Class<?> kCls = loader.loadClass("com.live2d.graphics3d.editableMesh.triangulation.k");
        final Object p0 =
                ptCls.getConstructor(float.class, float.class, int.class).newInstance(0f, 0f, 0);
        final Object p1 =
                ptCls.getConstructor(float.class, float.class, int.class).newInstance(1f, 2f, 1);
        final Object p2 =
                ptCls.getConstructor(float.class, float.class, int.class).newInstance(3f, 4f, 2);
        final Object e01 = edgeCls.getConstructor(ptCls, ptCls).newInstance(p0, p1);
        final Object e10 = edgeCls.getConstructor(ptCls, ptCls).newInstance(p1, p0);
        final Object e02 = edgeCls.getConstructor(ptCls, ptCls).newInstance(p0, p2);
        final Object k = kCls.getConstructor().newInstance();
        assertTrue((boolean) kCls.getMethod("a", edgeCls).invoke(k, e01));
        assertTrue((boolean) kCls.getMethod("a", edgeCls, boolean.class).invoke(k, e01, true));
        assertFalse((boolean) kCls.getMethod("a", edgeCls, boolean.class).invoke(k, e10, true));
        assertTrue((boolean) kCls.getMethod("a", edgeCls, boolean.class).invoke(k, e10, false));
        assertFalse((boolean) kCls.getMethod("a", edgeCls, boolean.class).invoke(k, e02, false));
    }

    private static Tri brutePointLocate(final MockTriangleList list, final Pt q) {
        for (final Tri t : list.set) {
            if (nativeTriangleContains(t, q.x, q.y)
                    && q.index != t.a.index
                    && q.index != t.b.index
                    && q.index != t.c.index) {
                return t;
            }
        }
        return null;
    }

    private static void assertEdgeIntersectionsMatchBruteForce(final MockTriangleList tris, final MockEdgeSet edges) {
        final List<Pt> expectedPairs = new ArrayList<>();
        for (final Edge ce : edges.a()) {
            for (final Tri t : tris.set) {
                checkNativeEdge(ce, t.a, t.b, expectedPairs);
                checkNativeEdge(ce, t.b, t.c, expectedPairs);
                checkNativeEdge(ce, t.c, t.a, expectedPairs);
            }
        }
        final Object[] actual = TriangulationSpatialIndex.findIntersectingEdgePairs(tris, edges);
        assertNotNull(actual);
        assertEquals(expectedPairs.size(), actual.length, "intersecting pair count mismatch");
        for (int i = 0; i < expectedPairs.size(); i++) {
            assertSame(expectedPairs.get(i), actual[i], "endpoint mismatch at slot " + i);
        }
    }

    private static void checkNativeEdge(final Edge ce, final Pt ta, final Pt tb, final List<Pt> out) {
        if (nativeIntersects(ce.a.x, ce.a.y, ce.b.x, ce.b.y, ta.x, ta.y, tb.x, tb.y)
                && ce.a.index != ta.index
                && ce.a.index != tb.index
                && ce.b.index != ta.index
                && ce.b.index != tb.index) {
            out.add(ta);
            out.add(tb);
        }
    }

    @SuppressWarnings("unchecked")
    private static void assertCollinearSoundness(
            final MockEdgeSet edges, final float px, final float py, final int pid) {
        final List<Edge> expected = new ArrayList<>();
        for (final Edge e : edges.a()) {
            if (pid == e.a.index || pid == e.b.index) {
                continue;
            }
            final float dx1 = e.b.x - e.a.x;
            final float dy1 = e.b.y - e.a.y;
            final float dx2 = px - e.a.x;
            final float dy2 = py - e.a.y;
            final float cross = dx1 * dy2 - dy1 * dx2;
            final float dot = dx1 * dx2 + dy1 * dy2;
            float angle = (float) Math.atan2(cross, dot);
            if (angle < 0.0f) {
                angle += 3.1415927f;
            }
            final float proj = (float) Math.sqrt(dx2 * dx2 + dy2 * dy2) * (float) Math.cos(angle);
            final float edgeLen = (float) Math.sqrt((double) dx1 * dx1 + (double) dy1 * dy1);
            if (angle <= 1.0e-6f && proj > 0.0f && proj < edgeLen) {
                expected.add(e);
            }
        }
        final Object raw = TriangulationSpatialIndex.findCollinearCandidates(edges, px, py, pid);
        assertNotSame(TriangulationSpatialIndex.FALLBACK, raw);
        final List<Edge> candidates = new ArrayList<>();
        if (raw != null) {
            final Iterator<Edge> it = (Iterator<Edge>) raw;
            while (it.hasNext()) {
                candidates.add(it.next());
            }
        }
        for (final Edge hit : expected) {
            assertTrue(candidates.contains(hit), "collinear candidate missed native split edge");
        }
    }

    private static boolean nativeTriangleContains(final Tri t, final float px, final float py) {
        final float s1 = px * (t.a.y - t.b.y) + t.a.x * (t.b.y - py) + t.b.x * (py - t.a.y);
        final float s2 = px * (t.b.y - t.c.y) + t.b.x * (t.c.y - py) + t.c.x * (py - t.b.y);
        final float s3 = px * (t.c.y - t.a.y) + t.c.x * (t.a.y - py) + t.a.x * (py - t.c.y);
        return (s1 >= 0.0f && s2 >= 0.0f && s3 >= 0.0f) || (s1 <= 0.0f && s2 <= 0.0f && s3 <= 0.0f);
    }

    private static boolean nativeIntersects(
            final float p1x,
            final float p1y,
            final float p2x,
            final float p2y,
            final float p3x,
            final float p3y,
            final float p4x,
            final float p4y) {
        final float f5 = (p4y - p3y) * (p4x - p1x) - (p4x - p3x) * (p4y - p1y);
        final float f6 = (p2x - p1x) * (p4y - p1y) - (p2y - p1y) * (p4x - p1x);
        final float f7 = (p2x - p1x) * (p4y - p3y) - (p2y - p1y) * (p4x - p3x);
        final float f8 = f5 / f7;
        final float f9 = f6 / f7;
        return 0.0f <= f8 && f8 <= 1.0f && 0.0f <= f9 && f9 <= 1.0f;
    }

    private static boolean nativePointInContour(final List<Pt> pts, final int[] c, final float qx, final float qy) {
        int count = 0;
        final int n = c.length;
        for (int i = 0; i < n; i++) {
            final Pt p1 = pts.get(c[i]);
            final Pt p2 = pts.get(c[(i + 1) % n]);
            if (qx > p1.x && qx > p2.x) continue;
            if ((qy >= p1.y || qy >= p2.y) && (qy < p1.y || qy < p2.y)) {
                final Pt up = p1.y > p2.y ? p1 : p2;
                final Pt down = p1.y > p2.y ? p2 : p1;
                final float cross = (down.x - up.x) * (qy - up.y) - (down.y - up.y) * (qx - up.x);
                if (cross < 0.0f) count++;
            }
        }
        return (count % 2) != 0;
    }

    private static byte[] minimalPointBytes() {
        final String owner = "com/live2d/graphics3d/editableMesh/triangulation/TriPoint";
        final ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "x", "F", null, null)
                .visitEnd();
        w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "y", "F", null, null)
                .visitEnd();
        w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "index", "I", null, null)
                .visitEnd();
        final MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(FFI)V", null, null);
        c.visitCode();
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitVarInsn(Opcodes.FLOAD, 1);
        c.visitFieldInsn(Opcodes.PUTFIELD, owner, "x", "F");
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitVarInsn(Opcodes.FLOAD, 2);
        c.visitFieldInsn(Opcodes.PUTFIELD, owner, "y", "F");
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitVarInsn(Opcodes.ILOAD, 3);
        c.visitFieldInsn(Opcodes.PUTFIELD, owner, "index", "I");
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(2, 4);
        c.visitEnd();
        for (final String[] g : new String[][] {{"getX", "F", "x"}, {"getY", "F", "y"}, {"getIndex", "I", "index"}}) {
            final MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, g[0], "()" + g[1], null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitFieldInsn(Opcodes.GETFIELD, owner, g[2], g[1]);
            m.visitInsn("F".equals(g[1]) ? Opcodes.FRETURN : Opcodes.IRETURN);
            m.visitMaxs(1, 1);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] minimalEdgeBytes() {
        final String owner = "com/live2d/graphics3d/editableMesh/triangulation/j";
        final String pt = "com/live2d/graphics3d/editableMesh/triangulation/TriPoint";
        final String ptDesc = "L" + pt + ";";
        final ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "a", ptDesc, null, null)
                .visitEnd();
        w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "b", ptDesc, null, null)
                .visitEnd();
        final MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "(" + ptDesc + ptDesc + ")V", null, null);
        c.visitCode();
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitVarInsn(Opcodes.ALOAD, 1);
        c.visitFieldInsn(Opcodes.PUTFIELD, owner, "a", ptDesc);
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitVarInsn(Opcodes.ALOAD, 2);
        c.visitFieldInsn(Opcodes.PUTFIELD, owner, "b", ptDesc);
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(2, 3);
        c.visitEnd();
        for (final String f : new String[] {"a", "b"}) {
            final MethodVisitor m = w.visitMethod(Opcodes.ACC_PUBLIC, f, "()" + ptDesc, null, null);
            m.visitCode();
            m.visitVarInsn(Opcodes.ALOAD, 0);
            m.visitFieldInsn(Opcodes.GETFIELD, owner, f, ptDesc);
            m.visitInsn(Opcodes.ARETURN);
            m.visitMaxs(1, 1);
            m.visitEnd();
        }
        w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] minimalIntrinsicsBytes() {
        final ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, "kotlin/jvm/internal/Intrinsics", null, "java/lang/Object", null);
        final MethodVisitor m = w.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "checkNotNullParameter",
                "(Ljava/lang/Object;Ljava/lang/String;)V",
                null,
                null);
        m.visitCode();
        m.visitInsn(Opcodes.RETURN);
        m.visitMaxs(0, 2);
        m.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }

    private static byte[] minimalEdgeSetHostBytes() {
        final String owner = TriangulationSpatialIndexTransformer.TARGET_EDGE_SET_INTERNAL_NAME;
        final String edgeDesc = "Lcom/live2d/graphics3d/editableMesh/triangulation/j;";
        final ClassWriter w = new ClassWriter(0);
        w.visit(Opcodes.V17, Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, owner, null, "java/lang/Object", null);
        w.visitField(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, "a", "Ljava/util/ArrayList;", null, null)
                .visitEnd();
        final MethodVisitor c = w.visitMethod(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        c.visitCode();
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
        c.visitVarInsn(Opcodes.ALOAD, 0);
        c.visitTypeInsn(Opcodes.NEW, "java/util/ArrayList");
        c.visitInsn(Opcodes.DUP);
        c.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/util/ArrayList", "<init>", "()V", false);
        c.visitFieldInsn(Opcodes.PUTFIELD, owner, "a", "Ljava/util/ArrayList;");
        c.visitInsn(Opcodes.RETURN);
        c.visitMaxs(3, 1);
        c.visitEnd();

        final MethodVisitor add = w.visitMethod(Opcodes.ACC_PUBLIC, "a", "(" + edgeDesc + ")Z", null, null);
        add.visitCode();
        add.visitVarInsn(Opcodes.ALOAD, 0);
        add.visitFieldInsn(Opcodes.GETFIELD, owner, "a", "Ljava/util/ArrayList;");
        add.visitVarInsn(Opcodes.ALOAD, 1);
        add.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
        add.visitInsn(Opcodes.IRETURN);
        add.visitMaxs(2, 2);
        add.visitEnd();

        final MethodVisitor contains = w.visitMethod(Opcodes.ACC_PUBLIC, "a", "(" + edgeDesc + "Z)Z", null, null);
        contains.visitCode();
        contains.visitVarInsn(Opcodes.ALOAD, 1);
        contains.visitLdcInsn("");
        contains.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                "kotlin/jvm/internal/Intrinsics",
                "checkNotNullParameter",
                "(Ljava/lang/Object;Ljava/lang/String;)V",
                false);
        contains.visitInsn(Opcodes.ICONST_0);
        contains.visitInsn(Opcodes.IRETURN);
        contains.visitMaxs(2, 3);
        contains.visitEnd();
        w.visitEnd();
        return w.toByteArray();
    }
}
