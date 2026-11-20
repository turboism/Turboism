package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Provides linear-time mesh outline extraction and post-solve edge merging for host meshes.
 *
 * <p>All algorithms preserve exact host ordering, floating-point rounding bits, winding polarity,
 * and edge priority upgrades while removing quadratic vertex-edge and triangle-edge scans.</p>
 */
public final class TextureAtlasMeshFastOps {

    private static final String LOG_COMPONENT = "texture-atlas-mesh";
    private static final String FAST_OPS_FAILED =
            "Fast mesh operation failed, falling back cleanly to native host code";
    private static final String MESH_PACKAGE = "com.live2d.graphics3d.editableMesh.";
    private static final String MESH_CLASS = MESH_PACKAGE + "GEditableMesh2";
    private static final String POINT_CLASS = MESH_PACKAGE + "MPoint2";
    private static final String EDGE_CLASS = MESH_PACKAGE + "MEdge";
    private static final String EDGE_TYPE_CLASS = EDGE_CLASS + "$EdgeType";
    private static final String COMPANION_CLASS = MESH_CLASS + "$a";
    private static final String AUTO_TRIANGULATION_NAME = "AUTO_TRIANGULATION";
    private static final String COMPANION_FIELD = "Companion";
    private static final String EDGES_FIELD = "_edges";
    private static final String EDGE_VERSION_FIELD = "_edge_edit_version";
    private static final String CW_PATCH_METHOD = "a";
    private static final String CLEAR_AUTO_METHOD = "clearAutoTriangulation";
    private static final String POINTS_METHOD = "getPoints";
    private static final String EDGES_METHOD = "getEdges";
    private static final String EDGE_INDEX1_METHOD = "getIndex1";
    private static final String EDGE_INDEX2_METHOD = "getIndex2";
    private static final String EDGE_TYPE_METHOD = "getType";
    private static final String EDGE_PRIORITY_METHOD = "a";
    private static final String POINT_X_METHOD = "getX";
    private static final String POINT_Y_METHOD = "getY";
    private static final String SET_CACHED_INDICES_METHOD = "setCached_indices$core";

    private static final float PI_F = (float) Math.PI;
    private static final float TWO_PI_F = PI_F * 2.0f;
    private static final int MIN_CONTOUR_VERTICES = 3;
    private static final int HALF_EDGES_PER_EDGE = 2;
    private static final int VERTEX_STRIDE_XY = 2;
    private static final int TRIANGLE_VERTEX_COUNT = 3;
    private static final int MIN_HASH_CAPACITY = 16;
    private static final long EMPTY_EDGE_KEY = -1L;
    private static final long LOWER_32_MASK = 0xFFFF_FFFFL;
    private static final int UPPER_32_SHIFT = 32;
    private static final int HASH_MIX_SHIFT = 33;
    private static final long HASH_MIX_MULTIPLIER = 0xFF51AFD7ED558CCDL;

    private static final ClassValue<Optional<MeshHost>> HOSTS = new ClassValue<>() {
        @Override
        protected Optional<MeshHost> computeValue(final Class<?> meshClass) {
            return MeshHost.bind(meshClass);
        }
    };

    private TextureAtlasMeshFastOps() {}

    /** Contour extraction output paired with flat point coordinates for worker reuse. */
    record OutlineSnapshot(List<int[]> contours, float[] positionsXY) {}

    /**
     * Replaces {@code editableMesh.b.b(GEditableMesh2)} with a two-pass linear incidence scan.
     *
     * @param mesh host {@code GEditableMesh2} instance
     * @return ordered closed contours, or {@code null} when host binding is unavailable
     */
    public static List<int[]> extractOutlines(final Object mesh) {
        if (mesh == null) return null;
        final Optional<MeshHost> hostOpt = HOSTS.get(mesh.getClass());
        if (hostOpt.isEmpty()) return null;
        try {
            return extractOutlines(hostOpt.get(), mesh, false).contours();
        } catch (IndexOutOfBoundsException outOfBounds) {
            throw outOfBounds;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.error(LOG_COMPONENT, FAST_OPS_FAILED, failure);
            return null;
        }
    }

    static OutlineSnapshot extractOutlinesAndPoints(final Object mesh) throws Throwable {
        final MeshHost host = HOSTS.get(mesh.getClass()).orElseThrow(IllegalStateException::new);
        return extractOutlines(host, mesh, true);
    }

    /**
     * Builds the per-vertex incident edge table in ascending edge order in {@code O(V + E)}.
     *
     * @param points host vertex list ({@code List<MPoint2>})
     * @param boundaryEdges filtered non-auto host edges ({@code List<MEdge>})
     * @return per-vertex array of incident indices into {@code boundaryEdges}
     */
    public static int[][] buildIncidentEdgeTable(final List<?> points, final List<?> boundaryEdges) {
        final int vertexCount = points.size();
        final int edgeCount = boundaryEdges.size();
        if (edgeCount == 0) {
            return new int[vertexCount][0];
        }
        final MeshHost host = HOSTS.get(
                        boundaryEdges.get(0).getClass().getClassLoader() != null
                                ? loadMeshClass(boundaryEdges.get(0).getClass().getClassLoader())
                                : Object.class)
                .orElse(null);
        if (host == null) {
            return buildIncidentEdgeTableSlow(vertexCount, boundaryEdges);
        }
        final int[] edgeU = new int[edgeCount];
        final int[] edgeV = new int[edgeCount];
        try {
            for (int e = 0; e < edgeCount; e++) {
                final Object edge = boundaryEdges.get(e);
                edgeU[e] = (int) host.edgeIndex1().invoke(edge);
                edgeV[e] = (int) host.edgeIndex2().invoke(edge);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            throw FatalErrors.propagate(failure);
        }
        return buildIncidentEdgeTable(vertexCount, edgeU, edgeV);
    }

    static int[][] buildIncidentEdgeTable(final int vertexCount, final int[] edgeU, final int[] edgeV) {
        final int edgeCount = edgeU.length;
        final int[] degree = new int[vertexCount];
        for (int e = 0; e < edgeCount; e++) {
            final int u = edgeU[e];
            final int v = edgeV[e];
            if (u >= 0 && u < vertexCount) {
                degree[u]++;
            }
            if (v != u && v >= 0 && v < vertexCount) {
                degree[v]++;
            }
        }
        final int[][] incident = new int[vertexCount][];
        for (int v = 0; v < vertexCount; v++) {
            incident[v] = new int[degree[v]];
        }
        final int[] cursor = new int[vertexCount];
        for (int e = 0; e < edgeCount; e++) {
            final int u = edgeU[e];
            final int v = edgeV[e];
            if (u >= 0 && u < vertexCount) {
                incident[u][cursor[u]++] = e;
            }
            if (v != u && v >= 0 && v < vertexCount) {
                incident[v][cursor[v]++] = e;
            }
        }
        return incident;
    }

    /**
     * Merges post-solve triangle edges in {@code O(E + T)} after {@code clearAutoTriangulation()}.
     *
     * @param mesh host {@code GEditableMesh2} instance
     * @param triangles raw solver output triangles
     * @return {@code true} when fast merge succeeded, or {@code false} to fall back cleanly
     */
    public static boolean mergeTrianglesAfterClear(final Object mesh, final int[][] triangles) {
        if (mesh == null || triangles == null) return false;
        final Optional<MeshHost> hostOpt = HOSTS.get(mesh.getClass());
        if (hostOpt.isEmpty()) return false;
        final MeshHost host = hostOpt.get();
        try {
            host.clearAutoTriangulation().invoke(mesh);
            final int[] uniquePairs = deduplicateTriangleEdges(triangles);
            final int triEdgeCallCount = triangles.length * TRIANGLE_VERTEX_COUNT;
            mergeAutoEdgePairs(host, mesh, uniquePairs, triEdgeCallCount);
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.error(LOG_COMPONENT, FAST_OPS_FAILED, failure);
            return false;
        }
    }

    static boolean applyPrepared(
            final Object mesh, final int[] cachedIndices, final int[] uniqueEdgePairs, final int triEdgeCallCount) {
        if (mesh == null) return false;
        final Optional<MeshHost> hostOpt = HOSTS.get(mesh.getClass());
        if (hostOpt.isEmpty()) return false;
        final MeshHost host = hostOpt.get();
        try {
            host.clearAutoTriangulation().invoke(mesh);
            mergeAutoEdgePairs(host, mesh, uniqueEdgePairs, triEdgeCallCount);
            host.setCachedIndices().invoke(mesh, cachedIndices);
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.error(LOG_COMPONENT, FAST_OPS_FAILED, failure);
            return false;
        }
    }

    static int[] deduplicateTriangleEdges(final int[][] triangles) {
        final int maxEdges = triangles.length * TRIANGLE_VERTEX_COUNT;
        if (maxEdges == 0) {
            return new int[0];
        }
        final int tableCap = tableCapacity(maxEdges);
        final long[] table = new long[tableCap];
        Arrays.fill(table, EMPTY_EDGE_KEY);
        final int mask = tableCap - 1;
        final int[] packed = new int[maxEdges * 2];
        int out = 0;
        for (final int[] tri : triangles) {
            final int a = tri[0];
            final int b = tri[1];
            final int c = tri[2];
            out = appendIfUniqueEdge(table, mask, packed, out, a, b);
            out = appendIfUniqueEdge(table, mask, packed, out, b, c);
            out = appendIfUniqueEdge(table, mask, packed, out, c, a);
        }
        return out == packed.length ? packed : Arrays.copyOf(packed, out);
    }

    static int[] orientTriangleIndices(
            final int[][] triangles, final float[] positionsXY, final boolean cwPatchOnCanvas) {
        final int triCount = triangles.length;
        final int[] indices = new int[triCount * TRIANGLE_VERTEX_COUNT];
        for (int i = 0; i < triCount; i++) {
            final int[] tri = triangles[i];
            final int i0 = tri[0];
            final int i1 = tri[1];
            final int i2 = tri[2];
            final int p0 = i0 * VERTEX_STRIDE_XY;
            final int p1 = i1 * VERTEX_STRIDE_XY;
            final int p2 = i2 * VERTEX_STRIDE_XY;
            final float x0 = positionsXY[p0];
            final float y0 = positionsXY[p0 + 1];
            final float x1 = positionsXY[p1];
            final float y1 = positionsXY[p1 + 1];
            final float x2 = positionsXY[p2];
            final float y2 = positionsXY[p2 + 1];
            final float area2 = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
            final int dst = i * TRIANGLE_VERTEX_COUNT;
            indices[dst] = i0;
            if ((area2 > 0.0f) == cwPatchOnCanvas) {
                indices[dst + 1] = i1;
                indices[dst + 2] = i2;
            } else {
                indices[dst + 1] = i2;
                indices[dst + 2] = i1;
            }
        }
        return indices;
    }

    static boolean queryCwPatchFlag(final Object mesh) {
        final MeshHost host = HOSTS.get(mesh.getClass()).orElseThrow(IllegalStateException::new);
        return host.cwPatchFlag();
    }

    private static OutlineSnapshot extractOutlines(
            final MeshHost host, final Object mesh, final boolean includePositions) throws Throwable {
        final List<?> points = (List<?>) host.points().invoke(mesh);
        final List<?> allEdges = (List<?>) host.edges().invoke(mesh);
        final int vertexCount = points.size();
        final float[] positionsXY = new float[vertexCount * VERTEX_STRIDE_XY];
        for (int i = 0; i < vertexCount; i++) {
            final Object pt = points.get(i);
            final int base = i * VERTEX_STRIDE_XY;
            positionsXY[base] = (float) host.pointX().invoke(pt);
            positionsXY[base + 1] = (float) host.pointY().invoke(pt);
        }

        final int allEdgeCount = allEdges.size();
        int[] edgeU = new int[allEdgeCount];
        int[] edgeV = new int[allEdgeCount];
        int edgeCount = 0;
        for (int i = 0; i < allEdgeCount; i++) {
            final Object edge = allEdges.get(i);
            if (host.edgeType().invoke(edge) != host.autoTriangulation()) {
                edgeU[edgeCount] = (int) host.edgeIndex1().invoke(edge);
                edgeV[edgeCount] = (int) host.edgeIndex2().invoke(edge);
                edgeCount++;
            }
        }
        if (edgeCount < allEdgeCount) {
            edgeU = Arrays.copyOf(edgeU, edgeCount);
            edgeV = Arrays.copyOf(edgeV, edgeCount);
        }

        final List<int[]> contours = extractOutlinesCore(vertexCount, positionsXY, edgeU, edgeV, host.cwPatchFlag());
        return new OutlineSnapshot(contours, includePositions ? positionsXY : null);
    }

    static List<int[]> extractOutlinesCore(
            final int vertexCount,
            final float[] positionsXY,
            final int[] edgeU,
            final int[] edgeV,
            final boolean cwPatchOnCanvas) {
        final int edgeCount = edgeU.length;
        final int[][] incidentEdges = buildIncidentEdgeTable(vertexCount, edgeU, edgeV);
        final float[] angles = new float[edgeCount];
        for (int e = 0; e < edgeCount; e++) {
            final int u = edgeU[e];
            final int v = edgeV[e];
            if (u < 0 || u >= vertexCount) {
                throw new IndexOutOfBoundsException("Index " + u + " out of bounds for length " + vertexCount);
            }
            if (v < 0 || v >= vertexCount) {
                throw new IndexOutOfBoundsException("Index " + v + " out of bounds for length " + vertexCount);
            }
            final float dy = positionsXY[v * VERTEX_STRIDE_XY + 1] - positionsXY[u * VERTEX_STRIDE_XY + 1];
            final float dx = positionsXY[v * VERTEX_STRIDE_XY] - positionsXY[u * VERTEX_STRIDE_XY];
            angles[e] = (float) Math.atan2(dy, dx);
        }

        final boolean[] visited = new boolean[edgeCount * HALF_EDGES_PER_EDGE];
        final ArrayList<int[]> contours = new ArrayList<>();
        final int[] loop = new int[vertexCount * HALF_EDGES_PER_EDGE];
        final int totalHalfEdges = edgeCount * HALF_EDGES_PER_EDGE;

        for (int i = 0; i < totalHalfEdges; i++) {
            if (visited[i]) continue;
            visited[i] = true;
            int curEdge = i / HALF_EDGES_PER_EDGE;
            boolean forward = (i % HALF_EDGES_PER_EDGE) == 0;
            final int startVertex = forward ? edgeU[curEdge] : edgeV[curEdge];
            int loopLen = 0;
            loop[loopLen++] = startVertex;
            int curVertex = forward ? edgeV[curEdge] : edgeU[curEdge];

            while (true) {
                if (loop.length <= loopLen) {
                    loopLen = 0;
                    break;
                }
                loop[loopLen++] = curVertex;
                final float baseAngle = forward ? angles[curEdge] + PI_F : angles[curEdge];
                float bestDelta = Float.MAX_VALUE;
                int bestEdge = -1;
                boolean bestForward = false;
                final int[] inc = incidentEdges[curVertex];
                for (int k = 0; k < inc.length; k++) {
                    final int candEdge = inc[k];
                    if (curEdge == candEdge) continue;
                    final boolean candForward = edgeU[candEdge] == curVertex;
                    final int halfIdx = candEdge * HALF_EDGES_PER_EDGE + (candForward ? 0 : 1);
                    if (visited[halfIdx]) continue;
                    final float candAngle = candForward ? angles[candEdge] : angles[candEdge] + PI_F;
                    float delta = cwPatchOnCanvas ? baseAngle - candAngle : candAngle - baseAngle;
                    while (delta < 0.0f) {
                        delta += TWO_PI_F;
                    }
                    while (delta > TWO_PI_F) {
                        delta -= TWO_PI_F;
                    }
                    if (delta < bestDelta) {
                        bestDelta = delta;
                        bestEdge = candEdge;
                        bestForward = candForward;
                    }
                }
                if (bestEdge == -1) break;
                visited[bestEdge * HALF_EDGES_PER_EDGE + (bestForward ? 0 : 1)] = true;
                curVertex = bestForward ? edgeV[bestEdge] : edgeU[bestEdge];
                curEdge = bestEdge;
                forward = bestForward;
                if (curVertex == startVertex) break;
            }

            if (loopLen < MIN_CONTOUR_VERTICES) continue;
            if (isClockwiseContour(loop, loopLen, positionsXY)) continue;

            final int[] reversed = new int[loopLen];
            for (int r = 0; r < loopLen; r++) {
                reversed[r] = loop[loopLen - r - 1];
            }
            if (hasUndirectedEdge(incidentEdges[reversed[0]], edgeU, edgeV, reversed[0], reversed[loopLen - 1])) {
                contours.add(reversed);
            }
        }
        return contours;
    }

    private static boolean isClockwiseContour(final int[] loop, final int len, final float[] positionsXY) {
        float yMax = Float.NEGATIVE_INFINITY;
        boolean cw = false;
        int prev = loop[len - 2];
        int curr = loop[len - 1];
        for (int i = 0; i < len; i++) {
            final int next = loop[i];
            final float cy = positionsXY[curr * VERTEX_STRIDE_XY + 1];
            if (cy > yMax) {
                final float cx = positionsXY[curr * VERTEX_STRIDE_XY];
                final float py = positionsXY[prev * VERTEX_STRIDE_XY + 1];
                final float px = positionsXY[prev * VERTEX_STRIDE_XY];
                final float ny = positionsXY[next * VERTEX_STRIDE_XY + 1];
                final float nx = positionsXY[next * VERTEX_STRIDE_XY];
                if (cy == py) {
                    cw = cx < px;
                } else if (cy == ny) {
                    cw = cx > nx;
                } else {
                    final float angNext = (float) Math.atan2(ny - cy, nx - cx);
                    final float angPrev = (float) Math.atan2(py - cy, px - cx);
                    cw = angNext < angPrev;
                }
                yMax = cy;
            }
            prev = curr;
            curr = next;
        }
        return cw;
    }

    private static boolean hasUndirectedEdge(
            final int[] inc, final int[] edgeU, final int[] edgeV, final int a, final int b) {
        for (int i = 0; i < inc.length; i++) {
            final int e = inc[i];
            final int u = edgeU[e];
            final int v = edgeV[e];
            if ((u == a && v == b) || (u == b && v == a)) {
                return true;
            }
        }
        return false;
    }

    private static void mergeAutoEdgePairs(
            final MeshHost host, final Object mesh, final int[] uniqueEdgePairs, final int triEdgeCallCount)
            throws Throwable {
        @SuppressWarnings("unchecked")
        final List<Object> edges = (List<Object>) host.edgesField().get(mesh);
        final int pairCount = uniqueEdgePairs.length / 2;
        final int existingCount = edges.size();
        final int cap = tableCapacity(existingCount + pairCount);
        final long[] keys = new long[cap];
        final int[] indices = new int[cap];
        Arrays.fill(keys, EMPTY_EDGE_KEY);
        final int mask = cap - 1;

        for (int i = 0; i < existingCount; i++) {
            final Object existing = edges.get(i);
            final int u = (int) host.edgeIndex1().invoke(existing);
            final int v = (int) host.edgeIndex2().invoke(existing);
            final long key = packEdgeKey(u, v);
            int slot = hashSlot(key, mask);
            while (keys[slot] != EMPTY_EDGE_KEY && keys[slot] != key) {
                slot = (slot + 1) & mask;
            }
            if (keys[slot] == EMPTY_EDGE_KEY) {
                keys[slot] = key;
                indices[slot] = i;
            }
        }

        final Object autoType = host.autoTriangulation();
        final byte autoPriority = host.autoPriority();
        for (int p = 0; p < uniqueEdgePairs.length; p += 2) {
            final int u = uniqueEdgePairs[p];
            final int v = uniqueEdgePairs[p + 1];
            final long key = packEdgeKey(u, v);
            int slot = hashSlot(key, mask);
            while (keys[slot] != EMPTY_EDGE_KEY && keys[slot] != key) {
                slot = (slot + 1) & mask;
            }
            if (keys[slot] == key) {
                final int idx = indices[slot];
                final Object existing = edges.get(idx);
                final Object type = host.edgeType().invoke(existing);
                if (type != autoType && (byte) host.edgePriority().invoke(type) < autoPriority) {
                    final int e1 = (int) host.edgeIndex1().invoke(existing);
                    final int e2 = (int) host.edgeIndex2().invoke(existing);
                    edges.set(idx, host.newEdge().invoke(e1, e2, autoType));
                }
            } else {
                final int newIdx = edges.size();
                edges.add(host.newEdge().invoke(u, v, autoType));
                keys[slot] = key;
                indices[slot] = newIdx;
            }
        }

        final int currentVersion = host.edgeVersionField().getInt(mesh);
        host.edgeVersionField().setInt(mesh, currentVersion + triEdgeCallCount);
    }

    private static int appendIfUniqueEdge(
            final long[] table, final int mask, final int[] outBuf, final int outPos, final int a, final int b) {
        final int u = Math.min(a, b);
        final int v = Math.max(a, b);
        final long key = packEdgeKey(u, v);
        int slot = hashSlot(key, mask);
        while (true) {
            final long existing = table[slot];
            if (existing == key) {
                return outPos;
            }
            if (existing == EMPTY_EDGE_KEY) {
                table[slot] = key;
                outBuf[outPos] = u;
                outBuf[outPos + 1] = v;
                return outPos + 2;
            }
            slot = (slot + 1) & mask;
        }
    }

    private static long packEdgeKey(final int a, final int b) {
        final int u = Math.min(a, b);
        final int v = Math.max(a, b);
        return (((long) u) << UPPER_32_SHIFT) | (v & LOWER_32_MASK);
    }

    private static int hashSlot(final long key, final int mask) {
        long h = key ^ (key >>> HASH_MIX_SHIFT);
        h *= HASH_MIX_MULTIPLIER;
        h ^= (h >>> HASH_MIX_SHIFT);
        return ((int) h) & mask;
    }

    private static int tableCapacity(final int expectedEntries) {
        int cap = Integer.highestOneBit(Math.max(MIN_HASH_CAPACITY, expectedEntries * 2 + 1) - 1) << 1;
        return Math.max(MIN_HASH_CAPACITY, cap);
    }

    private static int[][] buildIncidentEdgeTableSlow(final int vertexCount, final List<?> boundaryEdges) {
        final int edgeCount = boundaryEdges.size();
        try {
            final Method idx1 = boundaryEdges.get(0).getClass().getMethod(EDGE_INDEX1_METHOD);
            final Method idx2 = boundaryEdges.get(0).getClass().getMethod(EDGE_INDEX2_METHOD);
            final int[] edgeU = new int[edgeCount];
            final int[] edgeV = new int[edgeCount];
            for (int e = 0; e < edgeCount; e++) {
                final Object edge = boundaryEdges.get(e);
                edgeU[e] = (int) idx1.invoke(edge);
                edgeV[e] = (int) idx2.invoke(edge);
            }
            return buildIncidentEdgeTable(vertexCount, edgeU, edgeV);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Class<?> loadMeshClass(final ClassLoader loader) {
        try {
            return loader.loadClass(MESH_CLASS);
        } catch (ClassNotFoundException ignored) {
            return Object.class;
        }
    }

    private record MeshHost(
            MethodHandle points,
            MethodHandle edges,
            MethodHandle clearAutoTriangulation,
            MethodHandle setCachedIndices,
            MethodHandle pointX,
            MethodHandle pointY,
            MethodHandle edgeIndex1,
            MethodHandle edgeIndex2,
            MethodHandle edgeType,
            MethodHandle edgePriority,
            MethodHandle newEdge,
            MethodHandle cwPatchMethod,
            Field edgesField,
            Field edgeVersionField,
            Object companion,
            Object autoTriangulation,
            byte autoPriority) {
        static Optional<MeshHost> bind(final Class<?> meshClass) {
            try {
                final ClassLoader loader = meshClass.getClassLoader();
                if (loader == null || !MESH_CLASS.equals(meshClass.getName())) {
                    return Optional.empty();
                }
                final MethodHandles.Lookup lookup = MethodHandles.lookup();
                final Class<?> pointClass = loader.loadClass(POINT_CLASS);
                final Class<?> edgeClass = loader.loadClass(EDGE_CLASS);
                final Class<?> edgeTypeClass = loader.loadClass(EDGE_TYPE_CLASS);
                final Class<?> companionClass = loader.loadClass(COMPANION_CLASS);
                final Field companionField = meshClass.getField(COMPANION_FIELD);
                final Object companion = companionField.get(null);
                final Field edgesField = meshClass.getDeclaredField(EDGES_FIELD);
                edgesField.setAccessible(true);
                final Field edgeVersionField = meshClass.getDeclaredField(EDGE_VERSION_FIELD);
                edgeVersionField.setAccessible(true);
                final Object autoType =
                        edgeTypeClass.getField(AUTO_TRIANGULATION_NAME).get(null);
                final Method priorityMethod = edgeTypeClass.getMethod(EDGE_PRIORITY_METHOD);
                final byte autoPriority = (byte) priorityMethod.invoke(autoType);
                final Constructor<?> edgeCtor = edgeClass.getConstructor(int.class, int.class, edgeTypeClass);
                return Optional.of(new MeshHost(
                        lookup.unreflect(meshClass.getMethod(POINTS_METHOD)),
                        lookup.unreflect(meshClass.getMethod(EDGES_METHOD)),
                        lookup.unreflect(meshClass.getMethod(CLEAR_AUTO_METHOD)),
                        lookup.unreflect(meshClass.getMethod(SET_CACHED_INDICES_METHOD, int[].class)),
                        lookup.unreflect(pointClass.getMethod(POINT_X_METHOD)),
                        lookup.unreflect(pointClass.getMethod(POINT_Y_METHOD)),
                        lookup.unreflect(edgeClass.getMethod(EDGE_INDEX1_METHOD)),
                        lookup.unreflect(edgeClass.getMethod(EDGE_INDEX2_METHOD)),
                        lookup.unreflect(edgeClass.getMethod(EDGE_TYPE_METHOD)),
                        lookup.unreflect(priorityMethod),
                        lookup.unreflectConstructor(edgeCtor),
                        lookup.unreflect(companionClass.getMethod(CW_PATCH_METHOD)),
                        edgesField,
                        edgeVersionField,
                        companion,
                        autoType,
                        autoPriority));
            } catch (ReflectiveOperationException | LinkageError | SecurityException failure) {
                return Optional.empty();
            }
        }

        boolean cwPatchFlag() {
            try {
                return (boolean) cwPatchMethod.invoke(companion);
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw FatalErrors.propagate(failure);
            }
        }
    }
}
