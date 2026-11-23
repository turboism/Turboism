package dev.turboism.adapter.cubism.meshedit;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Replaces quadratic edge, duplicate-point, intersection, and triangle-enumeration loops across
 * editable meshes with indexed lookups, incremental edge maintenance, and spatial grids.
 */
public final class EditableMeshTopologyOptimizer {

    /** Returned to bytecode bridges when a lookup finds no matching entry under parity. */
    public static final Object NO_MATCH_SENTINEL = new Object();

    private static final int EMPTY_SLOT = -1;
    private static final long EMPTY_LONG_KEY = Long.MIN_VALUE;
    private static final int INT_MASK = 0x7FFFFFFF;
    private static final long LOW_INT_MASK = 0xFFFFFFFFL;
    private static final int PACK_SHIFT = 32;
    private static final int COORD_BITS_PER_VAL = 32;
    private static final int HASH_INITIAL_CAP = 16;
    private static final int INITIAL_CELL_CAP = 4;
    private static final int INITIAL_ADJ_CAP = 4;
    private static final int LOAD_FACTOR_NUM = 3;
    private static final int LOAD_FACTOR_DEN = 4;
    private static final int MIN_EDGES_FOR_CROSS_GRID = 24;
    private static final int MAX_CELLS_PER_EDGE = 32;
    private static final int INITIAL_EDGE_BUF = 32;
    private static final int BITS_PER_VERTEX_KEY = 21;
    private static final int MAX_PACKABLE_VERTEX = (1 << BITS_PER_VERTEX_KEY) - 1;

    private static final float UTIL_EPSILON = 1.0e-6f;
    private static final float UTIL_DEG_TO_RAD = 0.017453292f;
    private static final float UTIL_RAD_TO_DEG = 57.29578f;
    private static final float UTIL_TWO_PI = 3.1415927f * 2.0f;
    private static final float STRAIGHT_ANGLE_DEG = 180.0f;
    private static final double MIN_CELL_SPAN = 1.0e-5;
    private static final double GRID_MARGIN_RATIO = 1.0e-4;
    private static final double GRID_MIN_MARGIN = 1.0e-5;
    private static final double HALF = 0.5;
    private static final float ZERO_NEG_CANONICAL = 0.0f;

    private static final String EDGE_TYPE_AUTO = "AUTO_TRIANGULATION";
    private static final String EDGE_TYPE_USER = "USER_TRIANGULATION";

    private static final AtomicInteger FULL_BUILD_COUNT = new AtomicInteger();

    private static final MeshArrayFingerprint.WeakIdentityCache<MeshIndex> MESH_INDICES =
            new MeshArrayFingerprint.WeakIdentityCache<>();

    private static final ThreadLocal<CrossQueryScratch> CROSS_SCRATCH = ThreadLocal.withInitial(CrossQueryScratch::new);

    private static final ClassValue<MeshHandles> MESH_HANDLES = new ClassValue<>() {
        @Override
        protected MeshHandles computeValue(final Class<?> type) {
            return MeshHandles.resolve(type);
        }
    };
    private static final ClassValue<EdgeHandles> EDGE_HANDLES = new ClassValue<>() {
        @Override
        protected EdgeHandles computeValue(final Class<?> type) {
            return EdgeHandles.resolve(type);
        }
    };
    private static final ClassValue<PointHandles> POINT_HANDLES = new ClassValue<>() {
        @Override
        protected PointHandles computeValue(final Class<?> type) {
            return PointHandles.resolve(type);
        }
    };
    private static final ClassValue<VectorHandles> VECTOR_HANDLES = new ClassValue<>() {
        @Override
        protected VectorHandles computeValue(final Class<?> type) {
            return VectorHandles.resolve(type);
        }
    };
    private static final ClassValue<TriangleHandles> TRIANGLE_HANDLES = new ClassValue<>() {
        @Override
        protected TriangleHandles computeValue(final Class<?> type) {
            return TriangleHandles.resolve(type.getClassLoader());
        }
    };

    private EditableMeshTopologyOptimizer() {}

    static void resetBuildCountForTest() {
        FULL_BUILD_COUNT.set(0);
    }

    static int fullBuildCountForTest() {
        return FULL_BUILD_COUNT.get();
    }

    /**
     * Builds the per-vertex incident edge index matrix in {@code O(P + E)} time preserving exact
     * input edge ordering.
     *
     * @param points vertex list defining matrix row count
     * @param edges edge list defining incidence entries
     * @return jagged {@code int[pointCount][]} matrix mapping each vertex index to incident edge
     *     indices
     */
    public static int[][] buildIncidentEdgeMatrix(final List<?> points, final List<?> edges) {
        if (points == null || edges == null) {
            return null;
        }
        final int pointCount = points.size();
        final int edgeCount = edges.size();
        if (edgeCount == 0 || pointCount == 0) {
            return new int[pointCount][0];
        }
        try {
            final int[] edgeU = new int[edgeCount];
            final int[] edgeV = new int[edgeCount];
            final int[] degrees = new int[pointCount];
            EdgeHandles eh = null;
            for (int e = 0; e < edgeCount; e++) {
                final Object edge = edges.get(e);
                if (eh == null) {
                    eh = EDGE_HANDLES.get(edge.getClass());
                }
                final int u = (int) eh.index1.invokeExact(edge);
                final int v = (int) eh.index2.invokeExact(edge);
                edgeU[e] = u;
                edgeV[e] = v;
                if (u >= 0 && u < pointCount) {
                    degrees[u]++;
                }
                if (v >= 0 && v < pointCount && v != u) {
                    degrees[v]++;
                }
            }
            final int[][] matrix = new int[pointCount][];
            final int[] cursor = new int[pointCount];
            for (int p = 0; p < pointCount; p++) {
                matrix[p] = new int[degrees[p]];
            }
            for (int e = 0; e < edgeCount; e++) {
                final int u = edgeU[e];
                final int v = edgeV[e];
                if (u >= 0 && u < pointCount) {
                    matrix[u][cursor[u]++] = e;
                }
                if (v >= 0 && v < pointCount && v != u) {
                    matrix[v][cursor[v]++] = e;
                }
            }
            return matrix;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Builds an open-addressed undirected edge membership table for {@code O(1)} polygon closing
     * edge checks.
     *
     * @param edges edge list whose endpoints form undirected pairs
     * @return packed open-addressed table or {@code null} on reflection failure
     */
    public static long[] buildUndirectedEdgeTable(final List<?> edges) {
        if (edges == null) {
            return null;
        }
        try {
            final int count = edges.size();
            final int cap = tableCapacity(count);
            final long[] table = new long[cap];
            Arrays.fill(table, EMPTY_LONG_KEY);
            if (count == 0) {
                return table;
            }
            final int mask = cap - 1;
            EdgeHandles eh = null;
            for (int e = 0; e < count; e++) {
                final Object edge = edges.get(e);
                if (eh == null) {
                    eh = EDGE_HANDLES.get(edge.getClass());
                }
                final int u = (int) eh.index1.invokeExact(edge);
                final int v = (int) eh.index2.invokeExact(edge);
                insertUndirectedKey(table, mask, packUndirected(u, v));
            }
            return table;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Checks undirected edge presence against a table constructed by {@link #buildUndirectedEdgeTable}.
     *
     * @param table packed hash table
     * @param a first vertex index
     * @param b second vertex index
     * @return {@code true} if edge {@code (a, b)} or {@code (b, a)} exists
     */
    public static boolean containsUndirectedEdge(final long[] table, final int a, final int b) {
        if (table == null || table.length == 0) {
            return false;
        }
        final int mask = table.length - 1;
        final long key = packUndirected(a, b);
        int slot = ((int) MeshArrayFingerprint.mix(key)) & mask;
        while (true) {
            final long entry = table[slot];
            if (entry == key) {
                return true;
            }
            if (entry == EMPTY_LONG_KEY) {
                return false;
            }
            slot = (slot + 1) & mask;
        }
    }

    /**
     * Finds duplicate-coordinate vertex index groups in {@code O(P)} time while preserving first-seen
     * point order and IEEE {@code NaN} non-equality.
     *
     * @param mesh {@code GEditableMesh2} instance
     * @return list of index groups with size {@code >= 2}, or {@code null} to run host fallback
     */
    public static List<List<Integer>> findDuplicatePointGroups(final Object mesh) {
        if (mesh == null) {
            return null;
        }
        try {
            final MeshHandles mh = MESH_HANDLES.get(mesh.getClass());
            final List<?> points = (List<?>) mh.getPoints.invokeExact(mesh);
            final int count = points.size();
            final List<List<Integer>> groups = new ArrayList<>();
            if (count <= 1) {
                return groups;
            }
            PointHandles ph = null;
            final HashMap<Long, List<Integer>> byCoord = new HashMap<>(count * 2);
            for (int i = 0; i < count; i++) {
                final Object pt = points.get(i);
                if (ph == null) {
                    ph = POINT_HANDLES.get(pt.getClass());
                }
                final float x = (float) ph.getX.invokeExact(pt);
                final float y = (float) ph.getY.invokeExact(pt);
                if (Float.isNaN(x) || Float.isNaN(y)) {
                    continue;
                }
                final float cx = x == ZERO_NEG_CANONICAL ? ZERO_NEG_CANONICAL : x;
                final float cy = y == ZERO_NEG_CANONICAL ? ZERO_NEG_CANONICAL : y;
                final long key = (Float.floatToRawIntBits(cx) & LOW_INT_MASK)
                        | ((Float.floatToRawIntBits(cy) & LOW_INT_MASK) << COORD_BITS_PER_VAL);
                final int pointIdx = (int) ph.getIndex.invokeExact(pt);
                byCoord.computeIfAbsent(key, ignored -> new ArrayList<>(2)).add(pointIdx);
            }
            final boolean[] emitted = new boolean[count];
            for (int i = 0; i < count; i++) {
                final Object pt = points.get(i);
                final float x = (float) ph.getX.invokeExact(pt);
                final float y = (float) ph.getY.invokeExact(pt);
                if (Float.isNaN(x) || Float.isNaN(y)) {
                    continue;
                }
                final int pointIdx = (int) ph.getIndex.invokeExact(pt);
                if (pointIdx >= 0 && pointIdx < count && emitted[pointIdx]) {
                    continue;
                }
                final float cx = x == ZERO_NEG_CANONICAL ? ZERO_NEG_CANONICAL : x;
                final float cy = y == ZERO_NEG_CANONICAL ? ZERO_NEG_CANONICAL : y;
                final long key = (Float.floatToRawIntBits(cx) & LOW_INT_MASK)
                        | ((Float.floatToRawIntBits(cy) & LOW_INT_MASK) << COORD_BITS_PER_VAL);
                final List<Integer> group = byCoord.remove(key);
                if (group != null && group.size() > 1) {
                    groups.add(group);
                    for (final int memberIdx : group) {
                        if (memberIdx >= 0 && memberIdx < count) {
                            emitted[memberIdx] = true;
                        }
                    }
                }
            }
            return groups;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Enumerates unique mesh triangles in {@code O(E * deg)} time while matching Cubism's
     * coordinate-based {@code TriPoint.equals} deduplication semantics.
     *
     * @param mesh {@code GEditableMesh2} instance
     * @return list of {@code triangulation.l} triangles or {@code null} to run host fallback
     */
    public static List<Object> findMeshTriangles(final Object mesh) {
        if (mesh == null) {
            return null;
        }
        try {
            final TriangleHandles th = TRIANGLE_HANDLES.get(mesh.getClass());
            if (!th.available) {
                return null;
            }
            final MeshHandles mh = MESH_HANDLES.get(mesh.getClass());
            final List<?> points = (List<?>) mh.getPoints.invokeExact(mesh);
            final List<?> edges = (List<?>) mh.getEdges.invokeExact(mesh);
            final int pointCount = points.size();
            final int edgeCount = edges.size();
            final List<Object> triangles = new ArrayList<>();
            if (edgeCount < 3 || pointCount < 3) {
                return triangles;
            }
            if (pointCount > MAX_PACKABLE_VERTEX) {
                return null;
            }

            final int[] eU = new int[edgeCount];
            final int[] eV = new int[edgeCount];
            final int[] deg = new int[pointCount];
            final int tableCap = tableCapacity(edgeCount);
            final long[] edgeTable = new long[tableCap];
            Arrays.fill(edgeTable, EMPTY_LONG_KEY);
            final int mask = tableCap - 1;
            EdgeHandles eh = null;
            for (int e = 0; e < edgeCount; e++) {
                final Object edge = edges.get(e);
                if (eh == null) {
                    eh = EDGE_HANDLES.get(edge.getClass());
                }
                final int u = (int) eh.index1.invokeExact(edge);
                final int v = (int) eh.index2.invokeExact(edge);
                if (u < 0 || u >= pointCount || v < 0 || v >= pointCount) {
                    return null;
                }
                eU[e] = u;
                eV[e] = v;
                deg[u]++;
                if (v != u) {
                    deg[v]++;
                }
                insertUndirectedKey(edgeTable, mask, packUndirected(u, v));
            }

            final int[][] inc = new int[pointCount][];
            final int[] fill = new int[pointCount];
            for (int p = 0; p < pointCount; p++) {
                inc[p] = new int[deg[p]];
            }
            for (int e = 0; e < edgeCount; e++) {
                final int u = eU[e];
                final int v = eV[e];
                inc[u][fill[u]++] = e;
                if (v != u) {
                    inc[v][fill[v]++] = e;
                }
            }

            final float[] xs = new float[pointCount];
            final float[] ys = new float[pointCount];
            final long[] coordKeys = new long[pointCount];
            PointHandles ph = null;
            for (int p = 0; p < pointCount; p++) {
                final Object pt = points.get(p);
                if (ph == null) {
                    ph = POINT_HANDLES.get(pt.getClass());
                }
                final float x = (float) ph.getX.invokeExact(pt);
                final float y = (float) ph.getY.invokeExact(pt);
                if (Float.isNaN(x) || Float.isNaN(y)) {
                    return null;
                }
                xs[p] = x;
                ys[p] = y;
                final float cx = x == ZERO_NEG_CANONICAL ? ZERO_NEG_CANONICAL : x;
                final float cy = y == ZERO_NEG_CANONICAL ? ZERO_NEG_CANONICAL : y;
                coordKeys[p] = (Float.floatToRawIntBits(cx) & LOW_INT_MASK)
                        | ((Float.floatToRawIntBits(cy) & LOW_INT_MASK) << COORD_BITS_PER_VAL);
            }

            final Object[] triPoints = new Object[pointCount];
            final java.util.HashSet<CoordTriangleKey> seenTriangles = new java.util.HashSet<>(edgeCount);

            for (int e1 = 0; e1 < edgeCount; e1++) {
                final int u = eU[e1];
                final int v = eV[e1];
                final int[] incidentU = inc[u];
                for (final int e2 : incidentU) {
                    if (e2 == e1) {
                        continue;
                    }
                    final int w = eU[e2] == u ? eV[e2] : eU[e2];
                    if (w == u || w == v || !containsUndirectedIn(edgeTable, mask, w, v)) {
                        continue;
                    }
                    final CoordTriangleKey key = CoordTriangleKey.of(coordKeys[u], coordKeys[v], coordKeys[w]);
                    if (!seenTriangles.add(key)) {
                        continue;
                    }
                    final Object tpU = getOrCreateTriPoint(th, triPoints, points, u);
                    final Object tpV = getOrCreateTriPoint(th, triPoints, points, v);
                    final Object tpW = getOrCreateTriPoint(th, triPoints, points, w);
                    triangles.add(th.triCtor.invokeExact(tpU, tpV, tpW));
                }
            }
            return triangles;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Finds the index of an existing ordered edge {@code (i1, i2)} with {@code i1 < i2} in
     * {@code O(1)} amortized time.
     *
     * @param mesh {@code GEditableMesh2} instance
     * @param i1 lower vertex index
     * @param i2 higher vertex index
     * @param excludeTriangulation when {@code true}, ignores {@code AUTO_TRIANGULATION} and
     *     {@code USER_TRIANGULATION}
     * @return matched {@link Integer}, {@link #NO_MATCH_SENTINEL}, or {@code null} to run fallback
     */
    public static Object findExistingEdgeIndex(
            final Object mesh, final int i1, final int i2, final boolean excludeTriangulation) {
        if (mesh == null || i1 < 0 || i1 >= i2) {
            return null;
        }
        try {
            final MeshIndex index = ensureIndex(mesh, false, false);
            if (index == null) {
                return null;
            }
            final int edgeIdx = index.lookupFirstOrderedEdge(i1, i2, excludeTriangulation);
            return edgeIdx >= 0 ? Integer.valueOf(edgeIdx) : NO_MATCH_SENTINEL;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Looks up the first matching edge index matching Cubism's {@code getEdgeIndex} semantics.
     *
     * @param mesh {@code GEditableMesh2} instance
     * @param i1 first vertex index
     * @param i2 second vertex index
     * @return edge index, {@code -1} if absent, or {@link Integer#MIN_VALUE} to run fallback
     */
    public static int getEdgeIndexFast(final Object mesh, final int i1, final int i2) {
        if (mesh == null) {
            return Integer.MIN_VALUE;
        }
        final int min = Math.min(i1, i2);
        final int max = Math.max(min, i2);
        if (min == max) {
            return EMPTY_SLOT;
        }
        try {
            final MeshIndex index = ensureIndex(mesh, false, false);
            return index == null ? Integer.MIN_VALUE : index.lookupFirstOrderedEdge(min, max, false);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return Integer.MIN_VALUE;
        }
    }

    /**
     * Looks up the first matching edge matching Cubism's {@code getEdge} semantics.
     *
     * @param mesh {@code GEditableMesh2} instance
     * @param i1 first vertex index
     * @param i2 second vertex index
     * @return matched {@code MEdge}, {@link #NO_MATCH_SENTINEL} for {@code null}, or {@code null} for
     *     fallback
     */
    public static Object getEdgeFast(final Object mesh, final int i1, final int i2) {
        if (mesh == null) {
            return null;
        }
        final int min = Math.min(i1, i2);
        final int max = Math.max(min, i2);
        if (min == max) {
            return NO_MATCH_SENTINEL;
        }
        try {
            final MeshIndex index = ensureIndex(mesh, false, false);
            if (index == null) {
                return null;
            }
            final int edgeIdx = index.lookupFirstOrderedEdge(min, max, false);
            if (edgeIdx < 0) {
                return NO_MATCH_SENTINEL;
            }
            return index.edgeObjects[edgeIdx];
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Looks up a vertex index by permanent UID in {@code O(1)} amortized time.
     *
     * @param mesh {@code GEditableMesh2} instance
     * @param uid point unique identifier
     * @return vertex index, {@code -1} if absent, or {@link Integer#MIN_VALUE} to run fallback
     */
    public static int getPointIndexByUidFast(final Object mesh, final long uid) {
        if (mesh == null) {
            return Integer.MIN_VALUE;
        }
        try {
            final MeshIndex index = ensureIndex(mesh, false, false);
            return index == null ? Integer.MIN_VALUE : index.lookupPointByUid(uid);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return Integer.MIN_VALUE;
        }
    }

    /**
     * Finds an incident non-auto edge nearly collinear with {@code (i1, i2)} in {@code O(deg)} time.
     *
     * @param mesh {@code GEditableMesh2} instance
     * @param i1 lower vertex index
     * @param i2 higher vertex index
     * @param minRequiredDeg {@code GEditableMesh2.MIN_REQUIRED_DEG} threshold
     * @return matched third vertex {@link Integer}, {@link #NO_MATCH_SENTINEL} for none, or
     *     {@code null} to run fallback
     */
    public static Object findSimilarEdgeFast(
            final Object mesh, final int i1, final int i2, final float minRequiredDeg) {
        if (mesh == null || i1 < 0 || i1 >= i2) {
            return null;
        }
        try {
            final MeshIndex index = ensureIndex(mesh, true, false);
            if (index == null || i2 >= index.pointCount) {
                return null;
            }
            final int match = index.findSimilarEdge(i1, i2, minRequiredDeg);
            return match >= 0 ? Integer.valueOf(match) : NO_MATCH_SENTINEL;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Tests edge crossing against non-auto edges using a spatial grid when edge density justifies
     * indexing.
     *
     * @param mesh {@code GEditableMesh2} instance
     * @param p1 start point {@code GVector2}
     * @param i1 start vertex index
     * @param i2 end vertex index
     * @param ignoreUserTri ignores {@code USER_TRIANGULATION} intersections when true
     * @param removeUserTri removes intersecting {@code USER_TRIANGULATION} edges when true
     * @param ignoreEdge nullable edge excluded from intersection tests
     * @param checkCollinear includes angle-overlap crossing check introduced in newer hosts
     * @param minRequiredDeg {@code GEditableMesh2.MIN_REQUIRED_DEG} threshold
     * @return {@code 1} if crossed, {@code 0} if clear, or {@code -1} to run host fallback
     */
    public static int checkCrossFast(
            final Object mesh,
            final Object p1,
            final int i1,
            final int i2,
            final boolean ignoreUserTri,
            final boolean removeUserTri,
            final Object ignoreEdge,
            final boolean checkCollinear,
            final float minRequiredDeg) {
        if (mesh == null || p1 == null || i1 < 0 || i2 < 0) {
            return EMPTY_SLOT;
        }
        try {
            final MeshHandles mh = MESH_HANDLES.get(mesh.getClass());
            final MeshIndex index = ensureIndex(mesh, true, true);
            if (index == null || i2 >= index.pointCount || index.nonAutoCount < MIN_EDGES_FOR_CROSS_GRID) {
                return EMPTY_SLOT;
            }
            final VectorHandles vh = VECTOR_HANDLES.get(p1.getClass());
            final float p1x = (float) vh.getX.invokeExact(p1);
            final float p1y = (float) vh.getY.invokeExact(p1);
            final float p2x = index.pointX[i2];
            final float p2y = index.pointY[i2];
            final int[] candidates =
                    index.queryCrossCandidates(p1x, p1y, p2x, p2y, checkCollinear, CROSS_SCRATCH.get());
            if (candidates == null) {
                return EMPTY_SLOT;
            }
            final int candidateCount = CROSS_SCRATCH.get().lastCount;
            final boolean[] toRemove = removeUserTri ? new boolean[candidateCount] : null;
            boolean anyCross = false;
            for (int k = candidateCount - 1; k >= 0; k--) {
                final int nonAutoIdx = candidates[k];
                final int eIdx = index.nonAutoOriginalIndices[nonAutoIdx];
                final Object edgeObj = index.edgeObjects[eIdx];
                if (edgeObj == ignoreEdge || (ignoreEdge != null && edgeObj.equals(ignoreEdge))) {
                    continue;
                }
                final int eu = index.edgeU[eIdx];
                final int ev = index.edgeV[eIdx];
                if (eu == i1 || ev == i1 || eu == i2 || ev == i2) {
                    continue;
                }
                final float e1x = index.pointX[eu];
                final float e1y = index.pointY[eu];
                final float e2x = index.pointX[ev];
                final float e2y = index.pointY[ev];
                boolean hit = false;
                if (checkCollinear) {
                    hit = isCollinearOverlap(p1x, p1y, p2x, p2y, e1x, e1y, e2x, e2y, minRequiredDeg);
                }
                if (!hit) {
                    hit = segmentsIntersect(p1x, p1y, p2x, p2y, e1x, e1y, e2x, e2y);
                }
                if (!hit) {
                    continue;
                }
                final boolean isUserTri = index.edgeIsUserTri[eIdx];
                if (ignoreUserTri && isUserTri) {
                    continue;
                }
                if (removeUserTri && isUserTri) {
                    toRemove[k] = true;
                    continue;
                }
                anyCross = true;
                break;
            }
            if (removeUserTri && toRemove != null) {
                boolean removedAny = false;
                for (int k = candidateCount - 1; k >= 0; k--) {
                    if (toRemove[k]) {
                        final int eIdx = index.nonAutoOriginalIndices[candidates[k]];
                        mh.removeEdge.invokeExact(mesh, index.edgeObjects[eIdx]);
                        removedAny = true;
                    }
                }
                if (removedAny) {
                    invalidateMesh(mesh);
                }
            }
            return anyCross ? 1 : 0;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return EMPTY_SLOT;
        }
    }

    /**
     * Incrementally inserts a newly added edge when an existing index matches prior edge count.
     *
     * @param mesh {@code GEditableMesh2} instance
     * @param returnedIndex boxed return value from {@code addEdge}
     */
    public static void onEdgeAdded(final Object mesh, final Integer returnedIndex) {
        if (mesh == null || returnedIndex == null || returnedIndex < 0) {
            return;
        }
        final MeshIndex index = MESH_INDICES.get(mesh);
        if (index == null) {
            return;
        }
        try {
            final MeshHandles mh = MESH_HANDLES.get(mesh.getClass());
            final List<?> edges = (List<?>) mh.getEdges.invokeExact(mesh);
            final int edgeVer = (int) mh.edgeVersion.invokeExact(mesh);
            if (!index.tryAppendOrRefreshEdge(edges, edgeVer, returnedIndex)) {
                MESH_INDICES.remove(mesh);
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            MESH_INDICES.remove(mesh);
        }
    }

    /**
     * Refreshes cached edge metadata when {@code checkExitingTypedEdge} returns an existing edge
     * whose type may have been promoted, ignoring {@code null} misses.
     *
     * @param mesh {@code GEditableMesh2} instance
     * @param returnedIndex boxed index returned by {@code checkExitingTypedEdge}
     */
    public static void onTypedEdgeChecked(final Object mesh, final Integer returnedIndex) {
        if (mesh == null || returnedIndex == null || returnedIndex < 0) {
            return;
        }
        final MeshIndex index = MESH_INDICES.get(mesh);
        if (index == null) {
            return;
        }
        try {
            final MeshHandles mh = MESH_HANDLES.get(mesh.getClass());
            final List<?> edges = (List<?>) mh.getEdges.invokeExact(mesh);
            final int edgeVer = (int) mh.edgeVersion.invokeExact(mesh);
            if (!index.tryRefreshEdge(edges, edgeVer, returnedIndex)) {
                MESH_INDICES.remove(mesh);
            }
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            MESH_INDICES.remove(mesh);
        }
    }

    /**
     * Drops cached topology metadata for a mesh after structural edits.
     *
     * @param mesh {@code GEditableMesh2} instance
     */
    public static void invalidateMesh(final Object mesh) {
        if (mesh != null) {
            MESH_INDICES.remove(mesh);
        }
    }

    private static MeshIndex ensureIndex(
            final Object mesh, final boolean needCoordinates, final boolean needSpatialGrid) throws Throwable {
        final MeshHandles mh = MESH_HANDLES.get(mesh.getClass());
        final List<?> points = (List<?>) mh.getPoints.invokeExact(mesh);
        final List<?> edges = (List<?>) mh.getEdges.invokeExact(mesh);
        final int edgeVer = (int) mh.edgeVersion.invokeExact(mesh);
        final int posVer = (int) mh.posVersion.invokeExact(mesh);

        final MeshIndex existing = MESH_INDICES.get(mesh);
        if (existing != null && existing.matchesTopology(edgeVer, edges, points)) {
            if (needCoordinates || needSpatialGrid) {
                existing.ensureCoordinatesAndGrid(points, posVer, needSpatialGrid);
            }
            return existing;
        }

        final MeshIndex built = MeshIndex.build(points, edges, edgeVer, posVer, needCoordinates, needSpatialGrid);
        MESH_INDICES.remove(mesh);
        MESH_INDICES.computeIfAbsent(mesh, ignored -> built);
        return built;
    }

    private static boolean isCollinearOverlap(
            final float p1x,
            final float p1y,
            final float p2x,
            final float p2y,
            final float e1x,
            final float e1y,
            final float e2x,
            final float e2y,
            final float minRequiredDeg) {
        final float angleDeg = angleBetween(p2x - p1x, p2y - p1y, e2x - e1x, e2y - e1y) * UTIL_RAD_TO_DEG;
        final boolean smallAngle =
                angleDeg < minRequiredDeg || Math.abs(angleDeg - STRAIGHT_ANGLE_DEG) < minRequiredDeg;
        if (!smallAngle) {
            return false;
        }
        final float cosThresh = (float) (-Math.cos(minRequiredDeg * UTIL_DEG_TO_RAD));
        return isPointObtuseBetween(e1x, e1y, p1x, p1y, p2x, p2y, cosThresh)
                || isPointObtuseBetween(e2x, e2y, p1x, p1y, p2x, p2y, cosThresh);
    }

    private static boolean isPointObtuseBetween(
            final float qx,
            final float qy,
            final float p1x,
            final float p1y,
            final float p2x,
            final float p2y,
            final float cosThresh) {
        final float dx1 = qx - p1x;
        final float dy1 = qy - p1y;
        final float len1 = (float) Math.hypot(dx1, dy1);
        if (len1 <= UTIL_EPSILON) {
            return false;
        }
        final float dx2 = qx - p2x;
        final float dy2 = qy - p2y;
        final float len2 = (float) Math.hypot(dx2, dy2);
        if (len2 <= UTIL_EPSILON) {
            return false;
        }
        final float dot = dx1 * dx2 + dy1 * dy2;
        return ((dot / len1) / len2) < cosThresh;
    }

    private static boolean segmentsIntersect(
            final float p1x,
            final float p1y,
            final float p2x,
            final float p2y,
            final float e1x,
            final float e1y,
            final float e2x,
            final float e2y) {
        final float abx = e2x - e1x;
        final float aby = e2y - e1y;
        final float cdx = p2x - p1x;
        final float cdy = p2y - p1y;
        final float det = aby * cdx - abx * cdy;
        if (Math.abs(det) < UTIL_EPSILON) {
            return false;
        }
        final float inv = 1.0f / det;
        final float t = ((-p1x * cdy + e1x * cdy) + (p1y - e1y) * cdx) * inv;
        final float s = ((-p1x * aby + e1x * aby) + (p1y - e1y) * abx) * inv;
        return -UTIL_EPSILON < t && t < 1.0f + UTIL_EPSILON && -UTIL_EPSILON < s && s < 1.0f + UTIL_EPSILON;
    }

    private static float angleBetween(final float x1, final float y1, final float x2, final float y2) {
        final float a1 = (float) Math.atan2(y1, x1);
        final float a2 = (float) Math.atan2(y2, x2);
        float diff = a1 - a2;
        while ((double) diff < -Math.PI) {
            diff += UTIL_TWO_PI;
        }
        while ((double) diff > Math.PI) {
            diff -= UTIL_TWO_PI;
        }
        return diff < 0.0f ? -diff : diff;
    }

    private static long computePointDigest(final List<?> points) throws Throwable {
        final int size = points.size();
        if (size == 0) {
            return 0L;
        }
        final PointHandles ph = POINT_HANDLES.get(points.get(0).getClass());
        long h = MeshArrayFingerprint.mix(size);
        final int step = Math.max(1, size >>> 5);
        for (int i = 0; i < size; i += step) {
            h = MeshArrayFingerprint.mix(h ^ pointSig(ph, points.get(i)));
        }
        return MeshArrayFingerprint.mix(h ^ pointSig(ph, points.get(size - 1)));
    }

    private static long pointSig(final PointHandles ph, final Object pt) throws Throwable {
        final float x = (float) ph.getX.invokeExact(pt);
        final float y = (float) ph.getY.invokeExact(pt);
        final long uid = (long) ph.getUid.invokeExact(pt);
        return uid
                ^ (Float.floatToRawIntBits(x) & LOW_INT_MASK)
                ^ ((Float.floatToRawIntBits(y) & LOW_INT_MASK) << PACK_SHIFT);
    }

    private static Object getOrCreateTriPoint(
            final TriangleHandles th, final Object[] cache, final List<?> points, final int index) throws Throwable {
        Object existing = cache[index];
        if (existing == null) {
            existing = th.triPointCtor.invokeExact(points.get(index), index);
            cache[index] = existing;
        }
        return existing;
    }

    private static int tableCapacity(final int expectedItems) {
        int cap = HASH_INITIAL_CAP;
        final int target = Math.max(1, expectedItems) * LOAD_FACTOR_DEN / LOAD_FACTOR_NUM + 2;
        while (cap < target) {
            cap <<= 1;
        }
        return cap;
    }

    private static long packUndirected(final int a, final int b) {
        final int lo = Math.min(a, b);
        final int hi = Math.max(a, b);
        return (((long) lo) << PACK_SHIFT) | (hi & LOW_INT_MASK);
    }

    private static void insertUndirectedKey(final long[] table, final int mask, final long key) {
        int slot = ((int) MeshArrayFingerprint.mix(key)) & mask;
        while (true) {
            final long cur = table[slot];
            if (cur == key || cur == EMPTY_LONG_KEY) {
                table[slot] = key;
                return;
            }
            slot = (slot + 1) & mask;
        }
    }

    private static boolean containsUndirectedIn(final long[] table, final int mask, final int a, final int b) {
        final long key = packUndirected(a, b);
        int slot = ((int) MeshArrayFingerprint.mix(key)) & mask;
        while (true) {
            final long cur = table[slot];
            if (cur == key) {
                return true;
            }
            if (cur == EMPTY_LONG_KEY) {
                return false;
            }
            slot = (slot + 1) & mask;
        }
    }

    private static final class MeshIndex {

        int edgeVer;
        int edgeCount;
        final int pointCount;
        final Object firstPointObj;
        final Object lastPointObj;

        Object[] edgeObjects;
        int[] edgeU;
        int[] edgeV;
        boolean[] edgeIsAuto;
        boolean[] edgeIsTri;
        boolean[] edgeIsUserTri;

        float[] pointX;
        float[] pointY;
        int coordPosVer = Integer.MIN_VALUE;
        long coordPointDigest;
        boolean coordinatesLoaded;

        private long[] orderedEdgeKeys;
        private int[] orderedFirstIdx;
        private int[] orderedNonTriFirstIdx;
        private int orderedMask;
        private int orderedUsed;

        private long[] uidKeys;
        private int[] uidPointIdx;
        private int uidMask;

        private int[][] nonAutoIncidentEdges;
        private int[] nonAutoDegree;

        int nonAutoCount;
        int[] nonAutoOriginalIndices;
        int nonAutoGridCount;
        private int gridPosVer = Integer.MIN_VALUE;
        private long gridPointDigest;
        private double gMinX;
        private double gMaxX;
        private double gMinY;
        private double gMaxY;
        private int gridW;
        private int gridH;
        private double invCellW;
        private double invCellH;
        private int[][] gridCells;
        private int[] gridCellLens;
        private int[] gridOverflow;
        private int gridOverflowLen;

        private MeshIndex(
                final int edgeVer,
                final int edgeCount,
                final int pointCount,
                final Object firstPointObj,
                final Object lastPointObj,
                final Object[] edgeObjects,
                final int[] edgeU,
                final int[] edgeV,
                final boolean[] edgeIsAuto,
                final boolean[] edgeIsTri,
                final boolean[] edgeIsUserTri) {
            this.edgeVer = edgeVer;
            this.edgeCount = edgeCount;
            this.pointCount = pointCount;
            this.firstPointObj = firstPointObj;
            this.lastPointObj = lastPointObj;
            this.edgeObjects = edgeObjects;
            this.edgeU = edgeU;
            this.edgeV = edgeV;
            this.edgeIsAuto = edgeIsAuto;
            this.edgeIsTri = edgeIsTri;
            this.edgeIsUserTri = edgeIsUserTri;
        }

        static MeshIndex build(
                final List<?> points,
                final List<?> edges,
                final int edgeVer,
                final int posVer,
                final boolean loadCoordinates,
                final boolean buildGrid)
                throws Throwable {
            FULL_BUILD_COUNT.incrementAndGet();
            final int pCount = points.size();
            final int eCount = edges.size();
            final Object firstPt = pCount > 0 ? points.get(0) : null;
            final Object lastPt = pCount > 0 ? points.get(pCount - 1) : null;

            final long[] uids = new long[pCount];
            PointHandles ph = null;
            for (int p = 0; p < pCount; p++) {
                final Object pt = points.get(p);
                if (ph == null) {
                    ph = POINT_HANDLES.get(pt.getClass());
                }
                uids[p] = (long) ph.getUid.invokeExact(pt);
            }

            final int edgeCap = Math.max(INITIAL_EDGE_BUF, eCount + INITIAL_EDGE_BUF);
            final Object[] eObjs = new Object[edgeCap];
            final int[] eU = new int[edgeCap];
            final int[] eV = new int[edgeCap];
            final boolean[] eAuto = new boolean[edgeCap];
            final boolean[] eTri = new boolean[edgeCap];
            final boolean[] eUser = new boolean[edgeCap];
            EdgeHandles eh = null;
            int nonAuto = 0;
            for (int e = 0; e < eCount; e++) {
                final Object edge = edges.get(e);
                if (eh == null) {
                    eh = EDGE_HANDLES.get(edge.getClass());
                }
                eObjs[e] = edge;
                eU[e] = (int) eh.index1.invokeExact(edge);
                eV[e] = (int) eh.index2.invokeExact(edge);
                final Object typeObj = eh.type.invokeExact(edge);
                final boolean isAuto = typeObj == eh.autoEnum;
                final boolean isUser = typeObj == eh.userEnum;
                eAuto[e] = isAuto;
                eUser[e] = isUser;
                eTri[e] = isAuto || isUser;
                if (!isAuto) {
                    nonAuto++;
                }
            }

            final MeshIndex index =
                    new MeshIndex(edgeVer, eCount, pCount, firstPt, lastPt, eObjs, eU, eV, eAuto, eTri, eUser);
            index.nonAutoCount = nonAuto;
            index.buildUidTable(uids);
            index.rebuildEdgeTables();
            if (loadCoordinates || buildGrid) {
                index.ensureCoordinatesAndGrid(points, posVer, buildGrid);
            }
            return index;
        }

        boolean matchesTopology(final int currentEdgeVer, final List<?> edges, final List<?> points) {
            if (currentEdgeVer < this.edgeVer || this.edgeCount != edges.size() || this.pointCount != points.size()) {
                return false;
            }
            if (this.edgeCount > 0) {
                final int mid = this.edgeCount >>> 1;
                if (edges.get(0) != edgeObjects[0]
                        || edges.get(mid) != edgeObjects[mid]
                        || edges.get(this.edgeCount - 1) != edgeObjects[this.edgeCount - 1]) {
                    return false;
                }
            }
            if (this.pointCount > 0) {
                if (points.get(0) != firstPointObj || points.get(this.pointCount - 1) != lastPointObj) {
                    return false;
                }
            }
            // GEditableMesh2.addEdge bumps _edge_edit_version before validation; adopt unchanged topology versions in
            // O(1).
            this.edgeVer = currentEdgeVer;
            return true;
        }

        void ensureCoordinatesAndGrid(final List<?> points, final int posVer, final boolean needSpatialGrid)
                throws Throwable {
            boolean coordsChanged = !coordinatesLoaded || coordPosVer != posVer;
            long currentDigest = coordPointDigest;
            if (!coordsChanged) {
                currentDigest = computePointDigest(points);
                if (currentDigest != coordPointDigest) {
                    coordsChanged = true;
                }
            } else {
                currentDigest = computePointDigest(points);
            }
            if (coordsChanged) {
                if (pointX == null || pointX.length < pointCount) {
                    pointX = new float[pointCount];
                    pointY = new float[pointCount];
                }
                PointHandles ph = null;
                for (int p = 0; p < pointCount; p++) {
                    final Object pt = points.get(p);
                    if (ph == null) {
                        ph = POINT_HANDLES.get(pt.getClass());
                    }
                    pointX[p] = (float) ph.getX.invokeExact(pt);
                    pointY[p] = (float) ph.getY.invokeExact(pt);
                }
                coordPosVer = posVer;
                coordPointDigest = currentDigest;
                coordinatesLoaded = true;
                gridCells = null;
            }
            if (needSpatialGrid && (gridCells == null || gridPosVer != posVer || gridPointDigest != coordPointDigest)) {
                buildCrossGrid(posVer, coordPointDigest);
            }
        }

        boolean tryAppendOrRefreshEdge(final List<?> edges, final int newEdgeVer, final int index) throws Throwable {
            if (index < edgeCount && edges.size() == edgeCount) {
                return tryRefreshEdge(edges, newEdgeVer, index);
            }
            if (index != edgeCount || edges.size() != edgeCount + 1) {
                return false;
            }
            final Object edge = edges.get(index);
            final EdgeHandles eh = EDGE_HANDLES.get(edge.getClass());
            final int u = (int) eh.index1.invokeExact(edge);
            final int v = (int) eh.index2.invokeExact(edge);
            final Object typeObj = eh.type.invokeExact(edge);
            final boolean isAuto = typeObj == eh.autoEnum;
            final boolean isUser = typeObj == eh.userEnum;
            final boolean isTri = isAuto || isUser;
            if (index >= edgeObjects.length) {
                final int nextCap = Math.max(edgeObjects.length << 1, index + 1);
                edgeObjects = Arrays.copyOf(edgeObjects, nextCap);
                edgeU = Arrays.copyOf(edgeU, nextCap);
                edgeV = Arrays.copyOf(edgeV, nextCap);
                edgeIsAuto = Arrays.copyOf(edgeIsAuto, nextCap);
                edgeIsTri = Arrays.copyOf(edgeIsTri, nextCap);
                edgeIsUserTri = Arrays.copyOf(edgeIsUserTri, nextCap);
            }
            edgeObjects[index] = edge;
            edgeU[index] = u;
            edgeV[index] = v;
            edgeIsAuto[index] = isAuto;
            edgeIsTri[index] = isTri;
            edgeIsUserTri[index] = isUser;
            edgeCount++;
            edgeVer = newEdgeVer;
            insertOrderedEdgeEntry(u, v, index, isTri);
            if (!isAuto) {
                nonAutoCount++;
                if (nonAutoIncidentEdges != null) {
                    appendAdjacencyVertex(u, index);
                    if (v != u) {
                        appendAdjacencyVertex(v, index);
                    }
                }
                if (gridCells != null && !tryInsertNonAutoIntoGrid(index, u, v)) {
                    gridCells = null;
                }
            }
            return true;
        }

        boolean tryRefreshEdge(final List<?> edges, final int newEdgeVer, final int index) throws Throwable {
            if (index < 0 || index >= edgeCount || edges.size() != edgeCount) {
                return false;
            }
            final Object edge = edges.get(index);
            final EdgeHandles eh = EDGE_HANDLES.get(edge.getClass());
            final int u = (int) eh.index1.invokeExact(edge);
            final int v = (int) eh.index2.invokeExact(edge);
            if (u != edgeU[index] || v != edgeV[index]) {
                return false;
            }
            final Object typeObj = eh.type.invokeExact(edge);
            final boolean isAuto = typeObj == eh.autoEnum;
            final boolean isUser = typeObj == eh.userEnum;
            final boolean isTri = isAuto || isUser;
            final boolean wasAuto = edgeIsAuto[index];
            final boolean wasTri = edgeIsTri[index];
            edgeObjects[index] = edge;
            edgeIsAuto[index] = isAuto;
            edgeIsUserTri[index] = isUser;
            edgeIsTri[index] = isTri;
            edgeVer = newEdgeVer;
            if (wasTri && !isTri) {
                updateOrderedNonTriIdx(u, v, index);
            } else if (!wasTri && isTri) {
                rebuildEdgeTables();
            }
            if (wasAuto != isAuto) {
                nonAutoCount += isAuto ? -1 : 1;
                nonAutoIncidentEdges = null;
                nonAutoDegree = null;
                gridCells = null;
            }
            return true;
        }

        private void appendAdjacencyVertex(final int vertex, final int edgeIdx) {
            if (vertex < 0 || vertex >= pointCount) {
                return;
            }
            final int deg = nonAutoDegree[vertex];
            int[] bucket = nonAutoIncidentEdges[vertex];
            if (bucket.length == 0) {
                bucket = new int[INITIAL_ADJ_CAP];
                nonAutoIncidentEdges[vertex] = bucket;
            } else if (deg == bucket.length) {
                bucket = Arrays.copyOf(bucket, bucket.length << 1);
                nonAutoIncidentEdges[vertex] = bucket;
            }
            bucket[deg] = edgeIdx;
            nonAutoDegree[vertex] = deg + 1;
        }

        private boolean tryInsertNonAutoIntoGrid(final int edgeIdx, final int u, final int v) {
            if (!coordinatesLoaded || u < 0 || u >= pointCount || v < 0 || v >= pointCount) {
                return false;
            }
            final float ux = pointX[u];
            final float uy = pointY[u];
            final float vx = pointX[v];
            final float vy = pointY[v];
            if (!Float.isFinite(ux) || !Float.isFinite(uy) || !Float.isFinite(vx) || !Float.isFinite(vy)) {
                return false;
            }
            final double eMinX = Math.min(ux, vx);
            final double eMaxX = Math.max(ux, vx);
            final double eMinY = Math.min(uy, vy);
            final double eMaxY = Math.max(uy, vy);
            if (eMinX < gMinX || eMaxX > gMaxX || eMinY < gMinY || eMaxY > gMaxY) {
                return false;
            }
            if (nonAutoGridCount == nonAutoOriginalIndices.length) {
                nonAutoOriginalIndices = Arrays.copyOf(
                        nonAutoOriginalIndices, Math.max(INITIAL_EDGE_BUF, nonAutoOriginalIndices.length << 1));
            }
            final int k = nonAutoGridCount++;
            nonAutoOriginalIndices[k] = edgeIdx;

            final double segMargin =
                    Math.max(GRID_MIN_MARGIN, Math.hypot(vx - ux, vy - uy) * GRID_MARGIN_RATIO + UTIL_EPSILON * 2.0);
            final int c0 = clampCell((int) Math.floor((eMinX - segMargin - gMinX) * invCellW), gridW);
            final int c1 = clampCell((int) Math.floor((eMaxX + segMargin - gMinX) * invCellW), gridW);
            final int r0 = clampCell((int) Math.floor((eMinY - segMargin - gMinY) * invCellH), gridH);
            final int r1 = clampCell((int) Math.floor((eMaxY + segMargin - gMinY) * invCellH), gridH);
            if ((c1 - c0 + 1) * (r1 - r0 + 1) > MAX_CELLS_PER_EDGE) {
                if (gridOverflowLen == gridOverflow.length) {
                    gridOverflow = Arrays.copyOf(gridOverflow, Math.max(INITIAL_CELL_CAP, gridOverflow.length << 1));
                }
                gridOverflow[gridOverflowLen++] = k;
                return true;
            }
            for (int r = r0; r <= r1; r++) {
                final int row = r * gridW;
                for (int c = c0; c <= c1; c++) {
                    final int cellIdx = row + c;
                    int[] bucket = gridCells[cellIdx];
                    final int len = gridCellLens[cellIdx];
                    if (bucket == null) {
                        bucket = new int[INITIAL_CELL_CAP];
                        gridCells[cellIdx] = bucket;
                    } else if (len == bucket.length) {
                        bucket = Arrays.copyOf(bucket, bucket.length << 1);
                        gridCells[cellIdx] = bucket;
                    }
                    bucket[len] = k;
                    gridCellLens[cellIdx] = len + 1;
                }
            }
            return true;
        }

        private void buildUidTable(final long[] uids) {
            final int cap = tableCapacity(pointCount);
            uidKeys = new long[cap];
            uidPointIdx = new int[cap];
            Arrays.fill(uidKeys, EMPTY_LONG_KEY);
            Arrays.fill(uidPointIdx, EMPTY_SLOT);
            uidMask = cap - 1;
            for (int p = 0; p < pointCount; p++) {
                final long uid = uids[p];
                if (uid == EMPTY_LONG_KEY) {
                    continue;
                }
                int slot = ((int) MeshArrayFingerprint.mix(uid)) & uidMask;
                while (true) {
                    final long existing = uidKeys[slot];
                    if (existing == EMPTY_LONG_KEY) {
                        uidKeys[slot] = uid;
                        uidPointIdx[slot] = p;
                        break;
                    }
                    if (existing == uid) {
                        break;
                    }
                    slot = (slot + 1) & uidMask;
                }
            }
        }

        int lookupPointByUid(final long uid) {
            if (uid == EMPTY_LONG_KEY) {
                return EMPTY_SLOT;
            }
            int slot = ((int) MeshArrayFingerprint.mix(uid)) & uidMask;
            while (true) {
                final long cur = uidKeys[slot];
                if (cur == uid) {
                    return uidPointIdx[slot];
                }
                if (cur == EMPTY_LONG_KEY) {
                    return EMPTY_SLOT;
                }
                slot = (slot + 1) & uidMask;
            }
        }

        private void rebuildEdgeTables() {
            final int cap = tableCapacity(edgeCount);
            orderedEdgeKeys = new long[cap];
            orderedFirstIdx = new int[cap];
            orderedNonTriFirstIdx = new int[cap];
            Arrays.fill(orderedEdgeKeys, EMPTY_LONG_KEY);
            Arrays.fill(orderedFirstIdx, EMPTY_SLOT);
            Arrays.fill(orderedNonTriFirstIdx, EMPTY_SLOT);
            orderedMask = cap - 1;
            orderedUsed = 0;
            for (int e = 0; e < edgeCount; e++) {
                insertOrderedEdgeEntry(edgeU[e], edgeV[e], e, edgeIsTri[e]);
            }
        }

        private void insertOrderedEdgeEntry(final int u, final int v, final int edgeIdx, final boolean isTri) {
            if ((orderedUsed + 2) * LOAD_FACTOR_DEN >= orderedEdgeKeys.length * LOAD_FACTOR_NUM) {
                rehashOrderedTable();
            }
            final long key = (((long) u) << PACK_SHIFT) | (v & LOW_INT_MASK);
            int slot = ((int) MeshArrayFingerprint.mix(key)) & orderedMask;
            while (true) {
                final long cur = orderedEdgeKeys[slot];
                if (cur == EMPTY_LONG_KEY) {
                    orderedEdgeKeys[slot] = key;
                    orderedFirstIdx[slot] = edgeIdx;
                    orderedNonTriFirstIdx[slot] = isTri ? EMPTY_SLOT : edgeIdx;
                    orderedUsed++;
                    return;
                }
                if (cur == key) {
                    if (orderedFirstIdx[slot] < 0) {
                        orderedFirstIdx[slot] = edgeIdx;
                    }
                    if (!isTri && orderedNonTriFirstIdx[slot] < 0) {
                        orderedNonTriFirstIdx[slot] = edgeIdx;
                    }
                    return;
                }
                slot = (slot + 1) & orderedMask;
            }
        }

        private void updateOrderedNonTriIdx(final int u, final int v, final int edgeIdx) {
            final long key = (((long) u) << PACK_SHIFT) | (v & LOW_INT_MASK);
            int slot = ((int) MeshArrayFingerprint.mix(key)) & orderedMask;
            while (true) {
                final long cur = orderedEdgeKeys[slot];
                if (cur == key) {
                    if (orderedNonTriFirstIdx[slot] < 0 || edgeIdx < orderedNonTriFirstIdx[slot]) {
                        orderedNonTriFirstIdx[slot] = edgeIdx;
                    }
                    return;
                }
                if (cur == EMPTY_LONG_KEY) {
                    return;
                }
                slot = (slot + 1) & orderedMask;
            }
        }

        private void rehashOrderedTable() {
            final int newCap = orderedEdgeKeys.length << 1;
            final long[] nextKeys = new long[newCap];
            final int[] nextFirst = new int[newCap];
            final int[] nextNonTri = new int[newCap];
            Arrays.fill(nextKeys, EMPTY_LONG_KEY);
            Arrays.fill(nextFirst, EMPTY_SLOT);
            Arrays.fill(nextNonTri, EMPTY_SLOT);
            final int nextMask = newCap - 1;
            for (int i = 0; i < orderedEdgeKeys.length; i++) {
                final long key = orderedEdgeKeys[i];
                if (key == EMPTY_LONG_KEY) {
                    continue;
                }
                int slot = ((int) MeshArrayFingerprint.mix(key)) & nextMask;
                while (nextKeys[slot] != EMPTY_LONG_KEY) {
                    slot = (slot + 1) & nextMask;
                }
                nextKeys[slot] = key;
                nextFirst[slot] = orderedFirstIdx[i];
                nextNonTri[slot] = orderedNonTriFirstIdx[i];
            }
            orderedEdgeKeys = nextKeys;
            orderedFirstIdx = nextFirst;
            orderedNonTriFirstIdx = nextNonTri;
            orderedMask = nextMask;
        }

        int lookupFirstOrderedEdge(final int u, final int v, final boolean excludeTri) {
            final long key = (((long) u) << PACK_SHIFT) | (v & LOW_INT_MASK);
            int slot = ((int) MeshArrayFingerprint.mix(key)) & orderedMask;
            while (true) {
                final long cur = orderedEdgeKeys[slot];
                if (cur == key) {
                    return excludeTri ? orderedNonTriFirstIdx[slot] : orderedFirstIdx[slot];
                }
                if (cur == EMPTY_LONG_KEY) {
                    return EMPTY_SLOT;
                }
                slot = (slot + 1) & orderedMask;
            }
        }

        private void ensureNonAutoAdjacency() {
            if (nonAutoIncidentEdges != null) {
                return;
            }
            final int[] deg = new int[pointCount];
            for (int e = 0; e < edgeCount; e++) {
                if (edgeIsAuto[e]) {
                    continue;
                }
                final int u = edgeU[e];
                final int v = edgeV[e];
                if (u >= 0 && u < pointCount) {
                    deg[u]++;
                }
                if (v >= 0 && v < pointCount && v != u) {
                    deg[v]++;
                }
            }
            final int[][] inc = new int[pointCount][];
            final int[] pos = new int[pointCount];
            for (int p = 0; p < pointCount; p++) {
                inc[p] = deg[p] == 0 ? new int[0] : new int[Math.max(INITIAL_ADJ_CAP, deg[p])];
            }
            for (int e = 0; e < edgeCount; e++) {
                if (edgeIsAuto[e]) {
                    continue;
                }
                final int u = edgeU[e];
                final int v = edgeV[e];
                if (u >= 0 && u < pointCount) {
                    inc[u][pos[u]++] = e;
                }
                if (v >= 0 && v < pointCount && v != u) {
                    inc[v][pos[v]++] = e;
                }
            }
            nonAutoIncidentEdges = inc;
            nonAutoDegree = pos;
        }

        int findSimilarEdge(final int i1, final int i2, final float minRequiredDeg) {
            ensureNonAutoAdjacency();
            final int[] inc1 = nonAutoIncidentEdges[i1];
            final int[] inc2 = nonAutoIncidentEdges[i2];
            final int len1 = nonAutoDegree[i1];
            final int len2 = nonAutoDegree[i2];
            int p1 = 0;
            int p2 = 0;
            final float p1x = pointX[i1];
            final float p1y = pointY[i1];
            final float p2x = pointX[i2];
            final float p2y = pointY[i2];
            final float abx = p2x - p1x;
            final float aby = p2y - p1y;
            final float bax = p1x - p2x;
            final float bay = p1y - p2y;

            while (p1 < len1 || p2 < len2) {
                final int eIdx;
                if (p2 >= len2 || (p1 < len1 && inc1[p1] < inc2[p2])) {
                    eIdx = inc1[p1++];
                } else if (p1 >= len1 || inc2[p2] < inc1[p1]) {
                    eIdx = inc2[p2++];
                } else {
                    eIdx = inc1[p1++];
                    p2++;
                }
                final int u = edgeU[eIdx];
                final int v = edgeV[eIdx];
                if (u == i1 && v == i2) {
                    continue;
                }
                if (u != i1 && v != i2 && v != i1) {
                    continue;
                }
                final int other = (u == i1 || u == i2) ? v : u;
                if (other < 0 || other >= pointCount) {
                    continue;
                }
                final float ox = pointX[other];
                final float oy = pointY[other];
                final float angle1 = angleBetween(abx, aby, ox - p1x, oy - p1y);
                final float angle2 = angleBetween(bax, bay, ox - p2x, oy - p2y);
                final float maxDeg = Math.max(angle1, angle2) * UTIL_RAD_TO_DEG;
                if (maxDeg < minRequiredDeg) {
                    return other;
                }
            }
            return EMPTY_SLOT;
        }

        private void buildCrossGrid(final int posVer, final long currentPointDigest) {
            this.gridPosVer = posVer;
            this.gridPointDigest = currentPointDigest;
            final int mapCap = Math.max(INITIAL_EDGE_BUF, nonAutoCount << 1);
            final int[] nonAutoMap = new int[mapCap];
            int naIdx = 0;
            double minX = Double.POSITIVE_INFINITY;
            double minY = Double.POSITIVE_INFINITY;
            double maxX = Double.NEGATIVE_INFINITY;
            double maxY = Double.NEGATIVE_INFINITY;
            for (int p = 0; p < pointCount; p++) {
                final float px = pointX[p];
                final float py = pointY[p];
                if (Float.isFinite(px) && Float.isFinite(py)) {
                    minX = Math.min(minX, px);
                    maxX = Math.max(maxX, px);
                    minY = Math.min(minY, py);
                    maxY = Math.max(maxY, py);
                }
            }
            int finiteCount = 0;
            for (int e = 0; e < edgeCount; e++) {
                if (edgeIsAuto[e]) {
                    continue;
                }
                if (naIdx == nonAutoMap.length) {
                    break;
                }
                nonAutoMap[naIdx++] = e;
                final int u = edgeU[e];
                final int v = edgeV[e];
                if (u < 0 || u >= pointCount || v < 0 || v >= pointCount) {
                    continue;
                }
                final float ux = pointX[u];
                final float uy = pointY[u];
                final float vx = pointX[v];
                final float vy = pointY[v];
                if (!Float.isFinite(ux) || !Float.isFinite(uy) || !Float.isFinite(vx) || !Float.isFinite(vy)) {
                    continue;
                }
                finiteCount++;
            }
            this.nonAutoOriginalIndices = nonAutoMap;
            this.nonAutoGridCount = naIdx;
            if (finiteCount < MIN_EDGES_FOR_CROSS_GRID || naIdx == 0) {
                this.gridW = 1;
                this.gridH = 1;
                this.gMinX = 0.0;
                this.gMaxX = Double.NEGATIVE_INFINITY;
                this.gMinY = 0.0;
                this.gMaxY = Double.NEGATIVE_INFINITY;
                this.invCellW = 1.0;
                this.invCellH = 1.0;
                this.gridCells = new int[][] {sequentialArray(naIdx)};
                this.gridCellLens = new int[] {naIdx};
                this.gridOverflow = new int[0];
                this.gridOverflowLen = 0;
                return;
            }
            final double margin = Math.max(GRID_MIN_MARGIN, Math.max(maxX - minX, maxY - minY) * GRID_MARGIN_RATIO);
            this.gMinX = minX - margin;
            this.gMaxX = maxX + margin;
            this.gMinY = minY - margin;
            this.gMaxY = maxY + margin;
            final double spanX = Math.max(gMaxX - gMinX, MIN_CELL_SPAN);
            final double spanY = Math.max(gMaxY - gMinY, MIN_CELL_SPAN);
            final int[] dims = MeshArrayFingerprint.gridDimensions(spanX, spanY, naIdx);
            this.gridW = dims[0];
            this.gridH = dims[1];
            this.invCellW = gridW / spanX;
            this.invCellH = gridH / spanY;

            final int totalCells = gridW * gridH;
            final int[] counts = new int[totalCells];
            int overflowCount = 0;

            for (int k = 0; k < naIdx; k++) {
                final int e = nonAutoMap[k];
                final int u = edgeU[e];
                final int v = edgeV[e];
                if (u < 0 || u >= pointCount || v < 0 || v >= pointCount) {
                    overflowCount++;
                    continue;
                }
                final float ux = pointX[u];
                final float uy = pointY[u];
                final float vx = pointX[v];
                final float vy = pointY[v];
                if (!Float.isFinite(ux) || !Float.isFinite(uy) || !Float.isFinite(vx) || !Float.isFinite(vy)) {
                    overflowCount++;
                    continue;
                }
                final double eMinX = Math.min(ux, vx);
                final double eMaxX = Math.max(ux, vx);
                final double eMinY = Math.min(uy, vy);
                final double eMaxY = Math.max(uy, vy);
                final double segMargin = Math.max(
                        GRID_MIN_MARGIN, Math.hypot(vx - ux, vy - uy) * GRID_MARGIN_RATIO + UTIL_EPSILON * 2.0);
                final int c0 = clampCell((int) Math.floor((eMinX - segMargin - gMinX) * invCellW), gridW);
                final int c1 = clampCell((int) Math.floor((eMaxX + segMargin - gMinX) * invCellW), gridW);
                final int r0 = clampCell((int) Math.floor((eMinY - segMargin - gMinY) * invCellH), gridH);
                final int r1 = clampCell((int) Math.floor((eMaxY + segMargin - gMinY) * invCellH), gridH);
                if ((c1 - c0 + 1) * (r1 - r0 + 1) > MAX_CELLS_PER_EDGE) {
                    overflowCount++;
                    continue;
                }
                for (int r = r0; r <= r1; r++) {
                    final int row = r * gridW;
                    for (int c = c0; c <= c1; c++) {
                        counts[row + c]++;
                    }
                }
            }

            final int[][] cells = new int[totalCells][];
            final int[] writePos = new int[totalCells];
            for (int c = 0; c < totalCells; c++) {
                if (counts[c] > 0) {
                    cells[c] = new int[Math.max(INITIAL_CELL_CAP, counts[c])];
                }
            }
            final int[] overflow = new int[overflowCount];
            int overPos = 0;

            for (int k = 0; k < naIdx; k++) {
                final int e = nonAutoMap[k];
                final int u = edgeU[e];
                final int v = edgeV[e];
                if (u < 0 || u >= pointCount || v < 0 || v >= pointCount) {
                    overflow[overPos++] = k;
                    continue;
                }
                final float ux = pointX[u];
                final float uy = pointY[u];
                final float vx = pointX[v];
                final float vy = pointY[v];
                if (!Float.isFinite(ux) || !Float.isFinite(uy) || !Float.isFinite(vx) || !Float.isFinite(vy)) {
                    overflow[overPos++] = k;
                    continue;
                }
                final double eMinX = Math.min(ux, vx);
                final double eMaxX = Math.max(ux, vx);
                final double eMinY = Math.min(uy, vy);
                final double eMaxY = Math.max(uy, vy);
                final double segMargin = Math.max(
                        GRID_MIN_MARGIN, Math.hypot(vx - ux, vy - uy) * GRID_MARGIN_RATIO + UTIL_EPSILON * 2.0);
                final int c0 = clampCell((int) Math.floor((eMinX - segMargin - gMinX) * invCellW), gridW);
                final int c1 = clampCell((int) Math.floor((eMaxX + segMargin - gMinX) * invCellW), gridW);
                final int r0 = clampCell((int) Math.floor((eMinY - segMargin - gMinY) * invCellH), gridH);
                final int r1 = clampCell((int) Math.floor((eMaxY + segMargin - gMinY) * invCellH), gridH);
                if ((c1 - c0 + 1) * (r1 - r0 + 1) > MAX_CELLS_PER_EDGE) {
                    overflow[overPos++] = k;
                    continue;
                }
                for (int r = r0; r <= r1; r++) {
                    final int row = r * gridW;
                    for (int c = c0; c <= c1; c++) {
                        final int cell = row + c;
                        cells[cell][writePos[cell]++] = k;
                    }
                }
            }
            this.gridCells = cells;
            this.gridCellLens = writePos;
            this.gridOverflow = overflow;
            this.gridOverflowLen = overPos;
        }

        int[] queryCrossCandidates(
                final float p1x,
                final float p1y,
                final float p2x,
                final float p2y,
                final boolean checkCollinear,
                final CrossQueryScratch scratch) {
            if (!Float.isFinite(p1x) || !Float.isFinite(p1y) || !Float.isFinite(p2x) || !Float.isFinite(p2y)) {
                return null;
            }
            final double dx = (double) p2x - (double) p1x;
            final double dy = (double) p2y - (double) p1y;
            final double segLen = Math.hypot(dx, dy);
            double qMinX = Math.min(p1x, p2x);
            double qMaxX = Math.max(p1x, p2x);
            double qMinY = Math.min(p1y, p2y);
            double qMaxY = Math.max(p1y, p2y);
            if (checkCollinear) {
                final double midX = ((double) p1x + (double) p2x) * HALF;
                final double midY = ((double) p1y + (double) p2y) * HALF;
                final double radius = segLen * HALF;
                qMinX = Math.min(qMinX, midX - radius);
                qMaxX = Math.max(qMaxX, midX + radius);
                qMinY = Math.min(qMinY, midY - radius);
                qMaxY = Math.max(qMaxY, midY + radius);
            }
            final double pad = Math.max(GRID_MIN_MARGIN, segLen * GRID_MARGIN_RATIO + UTIL_EPSILON * 2.0);
            qMinX -= pad;
            qMaxX += pad;
            qMinY -= pad;
            qMaxY += pad;

            final int c0 = clampCell((int) Math.floor((qMinX - gMinX) * invCellW), gridW);
            final int c1 = clampCell((int) Math.floor((qMaxX - gMinX) * invCellW), gridW);
            final int r0 = clampCell((int) Math.floor((qMinY - gMinY) * invCellH), gridH);
            final int r1 = clampCell((int) Math.floor((qMaxY - gMinY) * invCellH), gridH);

            scratch.reset(nonAutoGridCount);
            for (int i = 0; i < gridOverflowLen; i++) {
                scratch.addUnique(gridOverflow[i]);
            }
            for (int r = r0; r <= r1; r++) {
                final int row = r * gridW;
                for (int c = c0; c <= c1; c++) {
                    final int cellIdx = row + c;
                    final int[] cell = gridCells[cellIdx];
                    if (cell != null) {
                        final int len = gridCellLens[cellIdx];
                        for (int i = 0; i < len; i++) {
                            scratch.addUnique(cell[i]);
                        }
                    }
                }
            }
            Arrays.sort(scratch.candidates, 0, scratch.lastCount);
            return scratch.candidates;
        }

        private static int clampCell(final int cell, final int limit) {
            if (cell < 0) {
                return 0;
            }
            return cell >= limit ? limit - 1 : cell;
        }

        private static int[] sequentialArray(final int length) {
            final int[] arr = new int[length];
            for (int i = 0; i < length; i++) {
                arr[i] = i;
            }
            return arr;
        }
    }

    private static final class CrossQueryScratch {

        int[] seenStamp = new int[INITIAL_EDGE_BUF];
        int[] candidates = new int[INITIAL_EDGE_BUF];
        int stamp = 1;
        int lastCount;

        void reset(final int nonAutoCount) {
            if (seenStamp.length < nonAutoCount) {
                seenStamp = new int[nonAutoCount];
            }
            stamp++;
            if (stamp == Integer.MAX_VALUE) {
                Arrays.fill(seenStamp, 0);
                stamp = 1;
            }
            lastCount = 0;
        }

        void addUnique(final int idx) {
            if (seenStamp[idx] == stamp) {
                return;
            }
            seenStamp[idx] = stamp;
            if (lastCount == candidates.length) {
                candidates = Arrays.copyOf(candidates, candidates.length << 1);
            }
            candidates[lastCount++] = idx;
        }
    }

    private static final class CoordTriangleKey {

        private final long a;
        private final long b;
        private final long c;
        private final int hash;

        private CoordTriangleKey(final long a, final long b, final long c) {
            this.a = a;
            this.b = b;
            this.c = c;
            final long mixed = MeshArrayFingerprint.mix(a) ^ (MeshArrayFingerprint.mix(b) * 31L) ^ (c * 997L);
            this.hash = (int) (mixed ^ (mixed >>> PACK_SHIFT));
        }

        static CoordTriangleKey of(final long u, final long v, final long w) {
            long k0 = u;
            long k1 = v;
            long k2 = w;
            if (k0 > k1) {
                final long t = k0;
                k0 = k1;
                k1 = t;
            }
            if (k1 > k2) {
                final long t = k1;
                k1 = k2;
                k2 = t;
            }
            if (k0 > k1) {
                final long t = k0;
                k0 = k1;
                k1 = t;
            }
            return new CoordTriangleKey(k0, k1, k2);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof CoordTriangleKey other)) {
                return false;
            }
            return a == other.a && b == other.b && c == other.c;
        }
    }

    private static final class MeshHandles {

        final MethodHandle getPoints;
        final MethodHandle getEdges;
        final MethodHandle edgeVersion;
        final MethodHandle posVersion;
        final MethodHandle removeEdge;

        private MeshHandles(
                final MethodHandle getPoints,
                final MethodHandle getEdges,
                final MethodHandle edgeVersion,
                final MethodHandle posVersion,
                final MethodHandle removeEdge) {
            this.getPoints = getPoints;
            this.getEdges = getEdges;
            this.edgeVersion = edgeVersion;
            this.posVersion = posVersion;
            this.removeEdge = removeEdge;
        }

        static MeshHandles resolve(final Class<?> type) {
            try {
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                final Class<?> mEdgeClass =
                        Class.forName("com.live2d.graphics3d.editableMesh.MEdge", false, type.getClassLoader());
                return new MeshHandles(
                        lookup.findVirtual(type, "getPoints", MethodType.methodType(List.class))
                                .asType(MethodType.methodType(List.class, Object.class)),
                        lookup.findVirtual(type, "getEdges", MethodType.methodType(List.class))
                                .asType(MethodType.methodType(List.class, Object.class)),
                        lookup.findVirtual(type, "get_edge_edit_version", MethodType.methodType(int.class))
                                .asType(MethodType.methodType(int.class, Object.class)),
                        lookup.findVirtual(type, "get_postion_edit_version", MethodType.methodType(int.class))
                                .asType(MethodType.methodType(int.class, Object.class)),
                        lookup.findVirtual(type, "removeEdge", MethodType.methodType(void.class, mEdgeClass))
                                .asType(MethodType.methodType(void.class, Object.class, Object.class)));
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException("Failed to bind mesh topology handles for " + type, failure);
            }
        }
    }

    private static final class EdgeHandles {

        final MethodHandle index1;
        final MethodHandle index2;
        final MethodHandle type;
        final Object autoEnum;
        final Object userEnum;

        private EdgeHandles(
                final MethodHandle index1,
                final MethodHandle index2,
                final MethodHandle type,
                final Object autoEnum,
                final Object userEnum) {
            this.index1 = index1;
            this.index2 = index2;
            this.type = type;
            this.autoEnum = autoEnum;
            this.userEnum = userEnum;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        static EdgeHandles resolve(final Class<?> type) {
            try {
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                final Class<?> edgeTypeClass = Class.forName(
                        "com.live2d.graphics3d.editableMesh.MEdge$EdgeType", false, type.getClassLoader());
                final Object autoVal = Enum.valueOf((Class<Enum>) edgeTypeClass.asSubclass(Enum.class), EDGE_TYPE_AUTO);
                final Object userVal = Enum.valueOf((Class<Enum>) edgeTypeClass.asSubclass(Enum.class), EDGE_TYPE_USER);
                return new EdgeHandles(
                        lookup.findVirtual(type, "getIndex1", MethodType.methodType(int.class))
                                .asType(MethodType.methodType(int.class, Object.class)),
                        lookup.findVirtual(type, "getIndex2", MethodType.methodType(int.class))
                                .asType(MethodType.methodType(int.class, Object.class)),
                        lookup.findVirtual(type, "getType", MethodType.methodType(edgeTypeClass))
                                .asType(MethodType.methodType(Object.class, Object.class)),
                        autoVal,
                        userVal);
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException("Failed to bind edge handles for " + type, failure);
            }
        }
    }

    private static final class PointHandles {

        final MethodHandle getX;
        final MethodHandle getY;
        final MethodHandle getIndex;
        final MethodHandle getUid;

        private PointHandles(
                final MethodHandle getX,
                final MethodHandle getY,
                final MethodHandle getIndex,
                final MethodHandle getUid) {
            this.getX = getX;
            this.getY = getY;
            this.getIndex = getIndex;
            this.getUid = getUid;
        }

        static PointHandles resolve(final Class<?> type) {
            try {
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new PointHandles(
                        lookup.findVirtual(type, "getX", MethodType.methodType(float.class))
                                .asType(MethodType.methodType(float.class, Object.class)),
                        lookup.findVirtual(type, "getY", MethodType.methodType(float.class))
                                .asType(MethodType.methodType(float.class, Object.class)),
                        lookup.findVirtual(type, "getIndex", MethodType.methodType(int.class))
                                .asType(MethodType.methodType(int.class, Object.class)),
                        lookup.findVirtual(type, "getPointUid", MethodType.methodType(long.class))
                                .asType(MethodType.methodType(long.class, Object.class)));
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException("Failed to bind point handles for " + type, failure);
            }
        }
    }

    private static final class VectorHandles {

        final MethodHandle getX;
        final MethodHandle getY;

        private VectorHandles(final MethodHandle getX, final MethodHandle getY) {
            this.getX = getX;
            this.getY = getY;
        }

        static VectorHandles resolve(final Class<?> type) {
            try {
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new VectorHandles(
                        lookup.findVirtual(type, "getX", MethodType.methodType(float.class))
                                .asType(MethodType.methodType(float.class, Object.class)),
                        lookup.findVirtual(type, "getY", MethodType.methodType(float.class))
                                .asType(MethodType.methodType(float.class, Object.class)));
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException("Failed to bind vector handles for " + type, failure);
            }
        }
    }

    private static final class TriangleHandles {

        final boolean available;
        final MethodHandle triPointCtor;
        final MethodHandle triCtor;

        private TriangleHandles(final boolean available, final MethodHandle triPointCtor, final MethodHandle triCtor) {
            this.available = available;
            this.triPointCtor = triPointCtor;
            this.triCtor = triCtor;
        }

        static TriangleHandles resolve(final ClassLoader loader) {
            if (loader == null) {
                return new TriangleHandles(false, null, null);
            }
            try {
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                final Class<?> gVec2 = Class.forName("com.live2d.graphics3d.type.GVector2", false, loader);
                final Class<?> triPoint =
                        Class.forName("com.live2d.graphics3d.editableMesh.triangulation.TriPoint", false, loader);
                final Class<?> triClass =
                        Class.forName("com.live2d.graphics3d.editableMesh.triangulation.l", false, loader);
                return new TriangleHandles(
                        true,
                        lookup.findConstructor(triPoint, MethodType.methodType(void.class, gVec2, int.class))
                                .asType(MethodType.methodType(Object.class, Object.class, int.class)),
                        lookup.findConstructor(
                                        triClass, MethodType.methodType(void.class, triPoint, triPoint, triPoint))
                                .asType(MethodType.methodType(Object.class, Object.class, Object.class, Object.class)));
            } catch (final ReflectiveOperationException | RuntimeException failure) {
                return new TriangleHandles(false, null, null);
            }
        }
    }
}
