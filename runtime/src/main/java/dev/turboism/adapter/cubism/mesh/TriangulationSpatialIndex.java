package dev.turboism.adapter.cubism.mesh;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, per-instance spatial and hash indices accelerating Cubism Delaunay triangulation
 * without modifying geometric output or vertex insertion order.
 */
public final class TriangulationSpatialIndex {
    /** Sentinel instructing woven call sites to execute the native host fallback loop. */
    public static final Object FALLBACK = new Object();

    private static final Object[] EMPTY_OBJECTS = new Object[0];
    private static final int MIN_TRIANGLES_FOR_GRID = 24;
    private static final int MIN_EDGES_FOR_GRID = 24;
    private static final int MAX_GRID_DIMENSION = 96;
    private static final int MAX_TOTAL_CELLS = 4096;
    private static final int MAX_CELLS_PER_ITEM = 20;
    private static final int GRID_SAMPLE_LIMIT = 64;
    private static final int PERCENTILE_NUMERATOR = 3;
    private static final int PERCENTILE_DENOMINATOR = 4;
    private static final float CELL_SCALE_PADDING = 1.25f;
    private static final float OUTLIER_SPAN_FACTOR = 16.0f;
    private static final int MIN_OVERSIZED_FOR_REBUILD = 24;
    private static final int REBUILD_OVERSIZED_DIVISOR = 6;
    private static final int MIN_GRID_REBUILD_MULTIPLIER = 2;
    private static final int SMALL_GRID_QUERY_CELL_LIMIT = 64;
    private static final int SORT_INSERTION_THRESHOLD = 32;
    private static final int INITIAL_ENTRIES_CAPACITY = 32;
    private static final int INITIAL_HASH_CAPACITY = 16;
    private static final int HASH_LOAD_DIVISOR = 2;
    private static final long HASH_MIX_CONSTANT = 0x9E3779B97F4A7C15L;
    private static final int INT_MASK_SHIFT = 32;
    private static final long LOW_INT_MASK = 0xffffffffL;
    private static final float MIN_CELL_SPAN = 1.0e-4f;
    private static final float AABB_REL_PAD = 1.0e-3f;
    private static final float AABB_MIN_PAD = 1.0e-3f;
    private static final float GRID_CELL_MARGIN_RATIO = 1.0e-5f;
    private static final float CENTROID_DIVISOR = 3.0f;
    private static final int CONTOUR_SLAB_MIN_EDGES = 64;
    private static final int CONTOUR_SLAB_COUNT = 32;
    private static final double COLLINEAR_ATAN_SLOPE = 2.0e-6;
    private static final double COLLINEAR_NEG_UNDERFLOW_SLOPE = 1.0e-30;
    private static final int CONTAINS_TRUE = 1;
    private static final int CONTAINS_FALSE = 0;
    private static final int CONTAINS_FALLBACK = -1;

    private static final WeakIdentityMap<TriGridState> TRI_STATES = new WeakIdentityMap<>();
    private static final WeakIdentityMap<EdgeSetState> EDGE_STATES = new WeakIdentityMap<>();

    private static final ClassValue<TriangleAccess> TRIANGLE_ACCESS = new ClassValue<>() {
        @Override
        protected TriangleAccess computeValue(final Class<?> type) {
            return TriangleAccess.resolve(type);
        }
    };
    private static final ClassValue<PointAccess> POINT_ACCESS = new ClassValue<>() {
        @Override
        protected PointAccess computeValue(final Class<?> type) {
            return PointAccess.resolve(type);
        }
    };
    private static final ClassValue<EdgeAccess> EDGE_ACCESS = new ClassValue<>() {
        @Override
        protected EdgeAccess computeValue(final Class<?> type) {
            return EdgeAccess.resolve(type);
        }
    };
    private static final ClassValue<MethodHandle> LIST_SIZE_ACCESS = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(final Class<?> type) {
            return resolveIntMethod(type, "a");
        }
    };
    private static final ClassValue<MethodHandle> EDGE_LIST_ACCESS = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(final Class<?> type) {
            return resolveObjectMethod(type, "a");
        }
    };
    private static final Comparator<TriEntry> BY_SEQ = Comparator.comparingLong(entry -> entry.seq);

    private TriangulationSpatialIndex() {}

    /** Records an added triangle and its corner coordinates for spatial indexing. */
    public static void onAdd(
            final Object list,
            final LinkedHashSet set,
            final Object tri,
            final Object pa,
            final float ax,
            final float ay,
            final int ia,
            final Object pb,
            final float bx,
            final float by,
            final int ib,
            final Object pc,
            final float cx,
            final float cy,
            final int ic) {
        if (list == null || tri == null) {
            return;
        }
        try {
            final TriGridState state = triState(list, set);
            if (state.dirty) {
                return;
            }
            if (set != null && state.activeCount != set.size() - 1) {
                state.markDirty();
                return;
            }
            final TriEntry entry = new TriEntry(tri, state.nextSeq++, pa, ax, ay, ia, pb, bx, by, ib, pc, cx, cy, ic);
            state.addEntry(entry);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            TRI_STATES.invalidate(list);
        }
    }

    /** Removes a triangle entry from the spatial grid when identity matches the stored entry. */
    public static void onRemove(final Object list, final LinkedHashSet set, final Object tri) {
        if (list == null || tri == null) {
            return;
        }
        try {
            final TriGridState state = TRI_STATES.peek(list);
            if (state == null || state.dirty) {
                return;
            }
            state.bindSet(set);
            final TriEntry removed = state.byTri.remove(tri);
            if (removed == null || (set != null && state.activeCount - 1 != set.size())) {
                state.markDirty();
                return;
            }
            state.removeEntry(removed);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            TRI_STATES.invalidate(list);
        }
    }

    /** Clears all tracked spatial grid entries for a cleared {@code TriangleList}. */
    public static void onClear(final Object list, final LinkedHashSet set) {
        if (list == null) {
            return;
        }
        try {
            final TriGridState state = TRI_STATES.peek(list);
            if (state != null) {
                state.bindSet(set);
                state.reset();
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            TRI_STATES.invalidate(list);
        }
    }

    /**
     * Returns the earliest-inserted triangle containing {@code (px, py)} that does not share
     * {@code pointIndex}, {@code null} when none exists, or {@link #FALLBACK} on unexpected state.
     */
    public static Object findContainingTriangle(
            final Object list, final float px, final float py, final int pointIndex) {
        if (list == null) {
            return FALLBACK;
        }
        try {
            final TriGridState state = syncTriState(list);
            if (state == null) {
                return FALLBACK;
            }
            if (state.activeCount == 0) {
                return null;
            }
            if (!Float.isFinite(px) || !Float.isFinite(py)) {
                return state.scanPointLinear(px, py, pointIndex);
            }
            state.ensureGridForQuery();
            return state.findContaining(px, py, pointIndex);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            TRI_STATES.invalidate(list);
            return FALLBACK;
        }
    }

    /**
     * Collects flattened {@code (u, v)} endpoint pairs for triangle edges intersecting constrained
     * edges in {@code edgeSet}, preserving exact native insertion order, or {@code null} to fall back.
     */
    public static Object[] findIntersectingEdgePairs(final Object list, final Object edgeSet) {
        if (list == null || edgeSet == null) {
            return null;
        }
        try {
            final TriGridState state = syncTriState(list);
            if (state == null) {
                return null;
            }
            if (state.activeCount == 0) {
                return EMPTY_OBJECTS;
            }
            final EdgeSetState edgeState = edgeState(edgeSet, null);
            final ArrayList<?> constraintList = edgeState.resolveList(edgeSet);
            if (constraintList == null || constraintList.isEmpty()) {
                return EMPTY_OBJECTS;
            }
            state.ensureGridForQuery();
            return state.findIntersectingEdges(constraintList);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            TRI_STATES.invalidate(list);
            return null;
        }
    }

    /** Updates the primitive edge hash index and edge-grid version after a constrained edge append. */
    public static void onEdgeAdd(
            final Object edgeSet,
            final ArrayList list,
            final Object edge,
            final int ia,
            final float ax,
            final float ay,
            final int ib,
            final float bx,
            final float by) {
        if (edgeSet == null || list == null) {
            return;
        }
        try {
            final EdgeSetState state = EDGE_STATES.peek(edgeSet);
            if (state == null) {
                return;
            }
            state.bindList(list);
            state.version++;
            if (state.dirty || state.indexedSize != list.size() - 1) {
                state.dirty = true;
                return;
            }
            state.insertEdgeKey(ia, ib);
            state.indexedSize++;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            EDGE_STATES.invalidate(edgeSet);
        }
    }

    /** Marks the edge hash index dirty after a direct edge removal. */
    public static void onEdgeMutate(final Object edgeSet, final ArrayList list) {
        if (edgeSet == null) {
            return;
        }
        try {
            final EdgeSetState state = EDGE_STATES.peek(edgeSet);
            if (state != null) {
                state.bindList(list);
                state.dirty = true;
                state.version++;
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            EDGE_STATES.invalidate(edgeSet);
        }
    }

    /**
     * Answers {@code k.a(edge, directed)} in {@code O(1)} using packed endpoint-index hash tables.
     *
     * @return {@code 1} if present, {@code 0} if absent, or {@code -1} to fall back to native scan
     */
    public static int containsEdge(
            final Object edgeSet, final ArrayList list, final int ia, final int ib, final boolean directed) {
        if (edgeSet == null || list == null) {
            return CONTAINS_FALLBACK;
        }
        if (list.isEmpty()) {
            return CONTAINS_FALSE;
        }
        try {
            final EdgeSetState state = edgeState(edgeSet, list);
            if (state.dirty || state.indexedSize != list.size()) {
                state.rebuildHashFromList(list);
            }
            return state.contains(ia, ib, directed) ? CONTAINS_TRUE : CONTAINS_FALSE;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            EDGE_STATES.invalidate(edgeSet);
            return CONTAINS_FALLBACK;
        }
    }

    /**
     * Filters constrained edges near {@code (px, py)} with zero vector allocations.
     *
     * @return {@code null} when no candidate edge passes the collinear guard, an {@link Iterator} of
     *     candidate edges in ascending index order, or {@link #FALLBACK} to run the native loop
     */
    public static Object findCollinearCandidates(
            final Object edgeSet, final float px, final float py, final int pointIndex) {
        if (edgeSet == null) {
            return FALLBACK;
        }
        if (!Float.isFinite(px) || !Float.isFinite(py)) {
            return FALLBACK;
        }
        try {
            final EdgeSetState state = edgeState(edgeSet, null);
            final ArrayList<?> list = state.resolveList(edgeSet);
            if (list == null) {
                return FALLBACK;
            }
            if (list.isEmpty()) {
                return null;
            }
            state.ensureEdgeGrid(list);
            return state.queryCollinearCandidates(px, py, pointIndex);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            EDGE_STATES.invalidate(edgeSet);
            return FALLBACK;
        }
    }

    /**
     * Removes triangles whose centroid lies outside every contour polygon, preserving exact parity
     * semantics while skipping out-of-AABB contours and large-contour irrelevant edges.
     */
    public static boolean pruneOutsideContours(
            final List<?> points, final List<int[]> contours, final Iterable<?> triangles) {
        if (contours == null) {
            return true;
        }
        if (points == null || triangles == null) {
            return false;
        }
        try {
            final int pointCount = points.size();
            if (pointCount == 0 && !contours.isEmpty()) {
                return false;
            }
            PointAccess pointAccess = null;
            final float[] px = new float[pointCount];
            final float[] py = new float[pointCount];
            for (int i = 0; i < pointCount; i++) {
                final Object pt = points.get(i);
                if (pointAccess == null) {
                    pointAccess = POINT_ACCESS.get(pt.getClass());
                }
                px[i] = pointAccess.getX(pt);
                py[i] = pointAccess.getY(pt);
            }

            final int contourCount = contours.size();
            final ContourData[] compiled = new ContourData[contourCount];
            for (int i = 0; i < contourCount; i++) {
                compiled[i] = ContourData.compile(contours.get(i), px, py);
            }

            final Iterator<?> iterator = triangles.iterator();
            TriangleAccess triAccess = null;
            while (iterator.hasNext()) {
                final Object tri = iterator.next();
                if (triAccess == null) {
                    triAccess = TRIANGLE_ACCESS.get(tri.getClass());
                }
                final Object pa = triAccess.a(tri);
                final Object pb = triAccess.b(tri);
                final Object pc = triAccess.c(tri);
                if (pointAccess == null) {
                    pointAccess = POINT_ACCESS.get(pa.getClass());
                }
                final float cx =
                        (pointAccess.getX(pa) + pointAccess.getX(pb) + pointAccess.getX(pc)) / CENTROID_DIVISOR;
                final float cy =
                        (pointAccess.getY(pa) + pointAccess.getY(pb) + pointAccess.getY(pc)) / CENTROID_DIVISOR;
                boolean inside = false;
                for (int i = 0; i < contourCount; i++) {
                    if (compiled[i].contains(cx, cy)) {
                        inside = true;
                        break;
                    }
                }
                if (!inside) {
                    iterator.remove();
                }
            }
            return true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return false;
        }
    }

    private static TriGridState triState(final Object list, final LinkedHashSet set) {
        final TriGridState existing = TRI_STATES.getOrCreate(list, TriGridState::new);
        existing.bindSet(set);
        return existing;
    }

    private static TriGridState syncTriState(final Object list) throws Throwable {
        final TriGridState state = TRI_STATES.getOrCreate(list, TriGridState::new);
        final int actualSize = state.readActualSize(list);
        if (state.dirty || state.activeCount != actualSize) {
            state.rebuildFromIterable((Iterable<?>) list);
        }
        return state;
    }

    private static EdgeSetState edgeState(final Object edgeSet, final ArrayList<?> list) {
        final EdgeSetState state = EDGE_STATES.getOrCreate(edgeSet, EdgeSetState::new);
        if (list != null) {
            state.bindList(list);
        }
        return state;
    }

    /** Exact float triangle-area orientation matching native {@code r.a(p1, p2, p3)}. */
    private static float triArea2(
            final float p1x, final float p1y, final float p2x, final float p2y, final float p3x, final float p3y) {
        return p2x * (p1y - p3y) + p1x * (p3y - p2y) + p3x * (p2y - p1y);
    }

    /** Exact half-plane inclusion matching native {@code r.b(a, b, c, pt) != 0}. */
    private static boolean exactPointInTriangle(final TriEntry t, final float px, final float py) {
        final float s1 = triArea2(t.ax, t.ay, px, py, t.bx, t.by);
        final float s2 = triArea2(t.bx, t.by, px, py, t.cx, t.cy);
        final float s3 = triArea2(t.cx, t.cy, px, py, t.ax, t.ay);
        return (s1 >= 0.0f && s2 >= 0.0f && s3 >= 0.0f) || (s1 <= 0.0f && s2 <= 0.0f && s3 <= 0.0f);
    }

    /** Exact segment intersection check matching native {@code r.a(p1, p2, p3, p4) != null}. */
    private static boolean intersectsScalar(
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

    private static boolean rejectCollinear(final float cross, final float dot) {
        if (!Float.isFinite(cross) || !Float.isFinite(dot)) {
            return false;
        }
        if (Math.abs(cross) < Float.MIN_NORMAL || Math.abs(dot) < Float.MIN_NORMAL) {
            return false;
        }
        if (dot > 0.0f) {
            if (cross >= 0.0f) {
                return (double) cross > (double) dot * COLLINEAR_ATAN_SLOPE;
            }
            return Math.abs((double) cross) > (double) dot * COLLINEAR_NEG_UNDERFLOW_SLOPE;
        }
        if (cross > 0.0f) {
            return true;
        }
        return Math.abs((double) cross) > (double) (-dot) * COLLINEAR_ATAN_SLOPE;
    }

    private static float aabbPad(final float spanX, final float spanY) {
        return Math.max(AABB_MIN_PAD, AABB_REL_PAD * Math.max(spanX, spanY));
    }

    private static void sortEntriesBySeq(final TriEntry[] buffer, final int count) {
        if (count <= SORT_INSERTION_THRESHOLD) {
            for (int i = 1; i < count; i++) {
                final TriEntry key = buffer[i];
                final long keySeq = key.seq;
                int j = i - 1;
                while (j >= 0 && buffer[j].seq > keySeq) {
                    buffer[j + 1] = buffer[j];
                    j--;
                }
                buffer[j + 1] = key;
            }
        } else {
            Arrays.sort(buffer, 0, count, BY_SEQ);
        }
    }

    private static void sortInts(final int[] values, final int count) {
        if (count <= SORT_INSERTION_THRESHOLD) {
            for (int i = 1; i < count; i++) {
                final int key = values[i];
                int j = i - 1;
                while (j >= 0 && values[j] > key) {
                    values[j + 1] = values[j];
                    j--;
                }
                values[j + 1] = key;
            }
        } else {
            Arrays.sort(values, 0, count);
        }
    }

    private static int clamp(final int value, final int min, final int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float quantile(final float[] sorted, final int length, final int num, final int den) {
        if (length <= 0) {
            return MIN_CELL_SPAN;
        }
        final int idx = clamp((length * num) / den, 0, length - 1);
        return sorted[idx];
    }

    private static MethodHandle resolveMethod(
            final Class<?> type, final String name, final Class<?> returnType, final String suffix) {
        try {
            final Method method = type.getMethod(name);
            MethodHandle handle;
            try {
                handle = MethodHandles.publicLookup().unreflect(method);
            } catch (IllegalAccessException packageVisibleEnclosing) {
                handle = MethodHandles.lookup().unreflect(method);
            }
            return handle.asType(MethodType.methodType(returnType, Object.class));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("missing " + type.getName() + "." + name + suffix, failure);
        }
    }

    private static MethodHandle resolveIntMethod(final Class<?> type, final String name) {
        return resolveMethod(type, name, int.class, "()I");
    }

    private static MethodHandle resolveFloatMethod(final Class<?> type, final String name) {
        return resolveMethod(type, name, float.class, "()F");
    }

    private static MethodHandle resolveObjectMethod(final Class<?> type, final String name) {
        return resolveMethod(type, name, Object.class, "()");
    }

    private static final class TriangleAccess {
        final MethodHandle a, b, c;

        private TriangleAccess(final MethodHandle a, final MethodHandle b, final MethodHandle c) {
            this.a = a;
            this.b = b;
            this.c = c;
        }

        static TriangleAccess resolve(final Class<?> type) {
            return new TriangleAccess(
                    resolveObjectMethod(type, "a"), resolveObjectMethod(type, "b"), resolveObjectMethod(type, "c"));
        }

        Object a(final Object tri) throws Throwable {
            return a.invokeExact(tri);
        }

        Object b(final Object tri) throws Throwable {
            return b.invokeExact(tri);
        }

        Object c(final Object tri) throws Throwable {
            return c.invokeExact(tri);
        }
    }

    private static final class PointAccess {
        final MethodHandle x, y, index;

        private PointAccess(final MethodHandle x, final MethodHandle y, final MethodHandle index) {
            this.x = x;
            this.y = y;
            this.index = index;
        }

        static PointAccess resolve(final Class<?> type) {
            return new PointAccess(
                    resolveFloatMethod(type, "getX"),
                    resolveFloatMethod(type, "getY"),
                    resolveIntMethod(type, "getIndex"));
        }

        float getX(final Object point) throws Throwable {
            return (float) x.invokeExact(point);
        }

        float getY(final Object point) throws Throwable {
            return (float) y.invokeExact(point);
        }

        int getIndex(final Object point) throws Throwable {
            return (int) index.invokeExact(point);
        }
    }

    private static final class EdgeAccess {
        final MethodHandle a, b;

        private EdgeAccess(final MethodHandle a, final MethodHandle b) {
            this.a = a;
            this.b = b;
        }

        static EdgeAccess resolve(final Class<?> type) {
            return new EdgeAccess(resolveObjectMethod(type, "a"), resolveObjectMethod(type, "b"));
        }

        Object a(final Object edge) throws Throwable {
            return a.invokeExact(edge);
        }

        Object b(final Object edge) throws Throwable {
            return b.invokeExact(edge);
        }
    }

    private static final class TriEntry {
        final Object tri;
        final long seq;
        final Object pa, pb, pc;
        final float ax, ay, bx, by, cx, cy;
        final int ia, ib, ic;
        final float minX, maxX, minY, maxY;
        int denseSlot;
        boolean inOversized;
        int gx0, gy0, gx1, gy1;

        TriEntry(
                final Object tri,
                final long seq,
                final Object pa,
                final float ax,
                final float ay,
                final int ia,
                final Object pb,
                final float bx,
                final float by,
                final int ib,
                final Object pc,
                final float cx,
                final float cy,
                final int ic) {
            this.tri = tri;
            this.seq = seq;
            this.pa = pa;
            this.ax = ax;
            this.ay = ay;
            this.ia = ia;
            this.pb = pb;
            this.bx = bx;
            this.by = by;
            this.ib = ib;
            this.pc = pc;
            this.cx = cx;
            this.cy = cy;
            this.ic = ic;
            final float rawMinX = Math.min(ax, Math.min(bx, cx));
            final float rawMaxX = Math.max(ax, Math.max(bx, cx));
            final float rawMinY = Math.min(ay, Math.min(by, cy));
            final float rawMaxY = Math.max(ay, Math.max(by, cy));
            final float pad = aabbPad(rawMaxX - rawMinX, rawMaxY - rawMinY);
            this.minX = rawMinX - pad;
            this.maxX = rawMaxX + pad;
            this.minY = rawMinY - pad;
            this.maxY = rawMaxY + pad;
        }
    }

    private static final class TriGridState {
        final IdentityHashMap<Object, TriEntry> byTri = new IdentityHashMap<>(INITIAL_ENTRIES_CAPACITY);
        TriEntry[] dense = new TriEntry[INITIAL_ENTRIES_CAPACITY];
        int activeCount;
        long nextSeq;
        boolean dirty;
        WeakReference<LinkedHashSet<?>> setRef;

        boolean gridBuilt;
        float originX, originY, invCellW, invCellH;
        int gridW, gridH;
        int rebuildCountThreshold = MIN_TRIANGLES_FOR_GRID;
        ArrayList<TriEntry>[] cells;
        final ArrayList<TriEntry> oversized = new ArrayList<>();

        int[] stamp = new int[INITIAL_ENTRIES_CAPACITY];
        int stampGen;
        TriEntry[] candBuf = new TriEntry[INITIAL_ENTRIES_CAPACITY];
        Object[] pairBuf = EMPTY_OBJECTS;

        @SuppressWarnings("ReferenceEquality") // Avoids O(N) AbstractSet.equals traversal on every mutation.
        void bindSet(final LinkedHashSet<?> set) {
            if (set != null && (setRef == null || setRef.get() != set)) {
                setRef = new WeakReference<>(set);
            }
        }

        int readActualSize(final Object list) throws Throwable {
            final LinkedHashSet<?> set = setRef != null ? setRef.get() : null;
            if (set != null) {
                return set.size();
            }
            return (int) LIST_SIZE_ACCESS.get(list.getClass()).invokeExact(list);
        }

        void markDirty() {
            dirty = true;
            gridBuilt = false;
        }

        void reset() {
            byTri.clear();
            Arrays.fill(dense, 0, activeCount, null);
            activeCount = 0;
            nextSeq = 0;
            dirty = false;
            clearGridStructures();
        }

        private void clearGridStructures() {
            gridBuilt = false;
            if (cells != null) {
                Arrays.fill(cells, null);
            }
            oversized.clear();
        }

        void addEntry(final TriEntry entry) {
            final int slot = activeCount++;
            if (slot == dense.length) {
                dense = Arrays.copyOf(dense, dense.length * 2);
                stamp = Arrays.copyOf(stamp, dense.length);
            }
            entry.denseSlot = slot;
            dense[slot] = entry;
            byTri.put(entry.tri, entry);
            if (gridBuilt) {
                insertIntoGrid(entry);
            }
        }

        void removeEntry(final TriEntry entry) {
            if (gridBuilt) {
                removeFromGrid(entry);
            }
            final int slot = entry.denseSlot;
            final int last = --activeCount;
            if (slot != last) {
                final TriEntry moved = dense[last];
                dense[slot] = moved;
                moved.denseSlot = slot;
            }
            dense[last] = null;
        }

        void rebuildFromIterable(final Iterable<?> iterable) throws Throwable {
            reset();
            TriangleAccess triAccess = null;
            PointAccess pointAccess = null;
            for (final Object tri : iterable) {
                if (triAccess == null) {
                    triAccess = TRIANGLE_ACCESS.get(tri.getClass());
                }
                final Object pa = triAccess.a(tri);
                final Object pb = triAccess.b(tri);
                final Object pc = triAccess.c(tri);
                if (pointAccess == null) {
                    pointAccess = POINT_ACCESS.get(pa.getClass());
                }
                final TriEntry entry = new TriEntry(
                        tri,
                        nextSeq++,
                        pa,
                        pointAccess.getX(pa),
                        pointAccess.getY(pa),
                        pointAccess.getIndex(pa),
                        pb,
                        pointAccess.getX(pb),
                        pointAccess.getY(pb),
                        pointAccess.getIndex(pb),
                        pc,
                        pointAccess.getX(pc),
                        pointAccess.getY(pc),
                        pointAccess.getIndex(pc));
                addEntry(entry);
            }
            dirty = false;
        }

        void ensureGridForQuery() {
            if (activeCount < MIN_TRIANGLES_FOR_GRID) {
                if (gridBuilt) {
                    clearGridStructures();
                }
                return;
            }
            if (!gridBuilt
                    || activeCount >= rebuildCountThreshold
                    || activeCount < rebuildCountThreshold / (MIN_GRID_REBUILD_MULTIPLIER * 2)
                    || (oversized.size() > MIN_OVERSIZED_FOR_REBUILD
                            && oversized.size() * REBUILD_OVERSIZED_DIVISOR > activeCount)) {
                buildGrid();
            }
        }

        @SuppressWarnings("unchecked")
        private void buildGrid() {
            clearGridStructures();
            final int count = activeCount;
            if (count < MIN_TRIANGLES_FOR_GRID) {
                return;
            }
            final int step = Math.max(1, count / GRID_SAMPLE_LIMIT);
            final int maxSamples = Math.min(GRID_SAMPLE_LIMIT, count);
            final float[] widths = new float[maxSamples];
            final float[] heights = new float[maxSamples];
            int samples = 0;
            for (int i = 0; i < count && samples < maxSamples; i += step) {
                final TriEntry e = dense[i];
                if (!Float.isFinite(e.minX)
                        || !Float.isFinite(e.maxX)
                        || !Float.isFinite(e.minY)
                        || !Float.isFinite(e.maxY)) {
                    continue;
                }
                widths[samples] = e.maxX - e.minX;
                heights[samples] = e.maxY - e.minY;
                samples++;
            }
            if (samples == 0) {
                return;
            }
            Arrays.sort(widths, 0, samples);
            Arrays.sort(heights, 0, samples);
            final float p75W = quantile(widths, samples, PERCENTILE_NUMERATOR, PERCENTILE_DENOMINATOR);
            final float p75H = quantile(heights, samples, PERCENTILE_NUMERATOR, PERCENTILE_DENOMINATOR);
            final float outlierW = Math.max(p75W * OUTLIER_SPAN_FACTOR, MIN_CELL_SPAN * OUTLIER_SPAN_FACTOR);
            final float outlierH = Math.max(p75H * OUTLIER_SPAN_FACTOR, MIN_CELL_SPAN * OUTLIER_SPAN_FACTOR);

            float minX = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            float allMinX = Float.POSITIVE_INFINITY;
            float allMaxX = Float.NEGATIVE_INFINITY;
            float allMinY = Float.POSITIVE_INFINITY;
            float allMaxY = Float.NEGATIVE_INFINITY;
            int coreCount = 0;
            for (int i = 0; i < count; i++) {
                final TriEntry e = dense[i];
                if (!Float.isFinite(e.minX)
                        || !Float.isFinite(e.maxX)
                        || !Float.isFinite(e.minY)
                        || !Float.isFinite(e.maxY)) {
                    continue;
                }
                allMinX = Math.min(allMinX, e.minX);
                allMaxX = Math.max(allMaxX, e.maxX);
                allMinY = Math.min(allMinY, e.minY);
                allMaxY = Math.max(allMaxY, e.maxY);
                if (e.maxX - e.minX <= outlierW && e.maxY - e.minY <= outlierH) {
                    minX = Math.min(minX, e.minX);
                    maxX = Math.max(maxX, e.maxX);
                    minY = Math.min(minY, e.minY);
                    maxY = Math.max(maxY, e.maxY);
                    coreCount++;
                }
            }
            if (coreCount * 2 < count) {
                minX = allMinX;
                maxX = allMaxX;
                minY = allMinY;
                maxY = allMaxY;
            }
            if (!Float.isFinite(minX) || !Float.isFinite(maxX) || !Float.isFinite(minY) || !Float.isFinite(maxY)) {
                return;
            }
            final float totalW = Math.max(maxX - minX, MIN_CELL_SPAN);
            final float totalH = Math.max(maxY - minY, MIN_CELL_SPAN);
            float cellW = Math.max(p75W * CELL_SCALE_PADDING, Math.max(totalW / MAX_GRID_DIMENSION, MIN_CELL_SPAN));
            float cellH = Math.max(p75H * CELL_SCALE_PADDING, Math.max(totalH / MAX_GRID_DIMENSION, MIN_CELL_SPAN));
            int gw = clamp((int) Math.ceil(totalW / cellW), 1, MAX_GRID_DIMENSION);
            int gh = clamp((int) Math.ceil(totalH / cellH), 1, MAX_GRID_DIMENSION);
            if (gw * gh > MAX_TOTAL_CELLS) {
                final double scale = Math.sqrt((double) (gw * gh) / (double) MAX_TOTAL_CELLS);
                cellW *= (float) scale;
                cellH *= (float) scale;
                gw = clamp((int) Math.ceil(totalW / cellW), 1, MAX_GRID_DIMENSION);
                gh = clamp((int) Math.ceil(totalH / cellH), 1, MAX_GRID_DIMENSION);
            }
            final int totalCells = gw * gh;
            if (cells == null || cells.length < totalCells) {
                cells = new ArrayList[totalCells];
            }
            originX = minX;
            originY = minY;
            invCellW = 1.0f / cellW;
            invCellH = 1.0f / cellH;
            gridW = gw;
            gridH = gh;
            gridBuilt = true;
            rebuildCountThreshold =
                    Math.max(MIN_TRIANGLES_FOR_GRID * MIN_GRID_REBUILD_MULTIPLIER, count * MIN_GRID_REBUILD_MULTIPLIER);

            for (int i = 0; i < count; i++) {
                insertIntoGrid(dense[i]);
            }
        }

        private void insertIntoGrid(final TriEntry e) {
            if (!Float.isFinite(e.minX)
                    || !Float.isFinite(e.maxX)
                    || !Float.isFinite(e.minY)
                    || !Float.isFinite(e.maxY)) {
                e.inOversized = true;
                oversized.add(e);
                return;
            }
            final float marginX = (1.0f / invCellW) * GRID_CELL_MARGIN_RATIO;
            final float marginY = (1.0f / invCellH) * GRID_CELL_MARGIN_RATIO;
            final int rawGx0 = (int) Math.floor((e.minX - marginX - originX) * invCellW);
            final int rawGx1 = (int) Math.floor((e.maxX + marginX - originX) * invCellW);
            final int rawGy0 = (int) Math.floor((e.minY - marginY - originY) * invCellH);
            final int rawGy1 = (int) Math.floor((e.maxY + marginY - originY) * invCellH);
            if (rawGx1 < 0 || rawGx0 >= gridW || rawGy1 < 0 || rawGy0 >= gridH) {
                e.inOversized = true;
                oversized.add(e);
                return;
            }
            final int gx0 = Math.max(0, rawGx0);
            final int gx1 = Math.min(gridW - 1, rawGx1);
            final int gy0 = Math.max(0, rawGy0);
            final int gy1 = Math.min(gridH - 1, rawGy1);
            final int spanCells = (gx1 - gx0 + 1) * (gy1 - gy0 + 1);
            if (spanCells > MAX_CELLS_PER_ITEM || rawGx0 < 0 || rawGx1 >= gridW || rawGy0 < 0 || rawGy1 >= gridH) {
                e.inOversized = true;
                oversized.add(e);
                return;
            }
            e.inOversized = false;
            e.gx0 = gx0;
            e.gx1 = gx1;
            e.gy0 = gy0;
            e.gy1 = gy1;
            for (int gy = gy0; gy <= gy1; gy++) {
                final int rowOffset = gy * gridW;
                for (int gx = gx0; gx <= gx1; gx++) {
                    final int cellIndex = rowOffset + gx;
                    ArrayList<TriEntry> bucket = cells[cellIndex];
                    if (bucket == null) {
                        bucket = new ArrayList<>(4);
                        cells[cellIndex] = bucket;
                    }
                    bucket.add(e);
                }
            }
        }

        private void removeFromGrid(final TriEntry e) {
            if (e.inOversized) {
                swapRemove(oversized, e);
                return;
            }
            for (int gy = e.gy0; gy <= e.gy1; gy++) {
                final int rowOffset = gy * gridW;
                for (int gx = e.gx0; gx <= e.gx1; gx++) {
                    final ArrayList<TriEntry> bucket = cells[rowOffset + gx];
                    if (bucket != null) {
                        swapRemove(bucket, e);
                    }
                }
            }
        }

        private static void swapRemove(final ArrayList<TriEntry> list, final TriEntry target) {
            for (int i = list.size() - 1; i >= 0; i--) {
                if (list.get(i) == target) {
                    final int last = list.size() - 1;
                    if (i != last) {
                        list.set(i, list.get(last));
                    }
                    list.remove(last);
                    return;
                }
            }
        }

        Object scanPointLinear(final float px, final float py, final int pointIndex) {
            TriEntry best = null;
            long bestSeq = Long.MAX_VALUE;
            for (int i = 0; i < activeCount; i++) {
                final TriEntry e = dense[i];
                if (e.seq >= bestSeq) {
                    continue;
                }
                if (pointIndex == e.ia || pointIndex == e.ib || pointIndex == e.ic) {
                    continue;
                }
                if (exactPointInTriangle(e, px, py)) {
                    best = e;
                    bestSeq = e.seq;
                }
            }
            return best != null ? best.tri : null;
        }

        Object findContaining(final float px, final float py, final int pointIndex) {
            if (!gridBuilt) {
                TriEntry best = null;
                long bestSeq = Long.MAX_VALUE;
                for (int i = 0; i < activeCount; i++) {
                    final TriEntry e = dense[i];
                    if (e.seq >= bestSeq) {
                        continue;
                    }
                    if (px < e.minX || px > e.maxX || py < e.minY || py > e.maxY) {
                        continue;
                    }
                    if (pointIndex == e.ia || pointIndex == e.ib || pointIndex == e.ic) {
                        continue;
                    }
                    if (exactPointInTriangle(e, px, py)) {
                        best = e;
                        bestSeq = e.seq;
                    }
                }
                return best != null ? best.tri : null;
            }
            TriEntry best = null;
            long bestSeq = Long.MAX_VALUE;
            final int gx = (int) Math.floor((px - originX) * invCellW);
            final int gy = (int) Math.floor((py - originY) * invCellH);
            if (gx >= 0 && gx < gridW && gy >= 0 && gy < gridH) {
                final ArrayList<TriEntry> bucket = cells[gy * gridW + gx];
                if (bucket != null) {
                    for (int i = bucket.size() - 1; i >= 0; i--) {
                        final TriEntry e = bucket.get(i);
                        if (e.seq >= bestSeq
                                || px < e.minX
                                || px > e.maxX
                                || py < e.minY
                                || py > e.maxY
                                || pointIndex == e.ia
                                || pointIndex == e.ib
                                || pointIndex == e.ic) {
                            continue;
                        }
                        if (exactPointInTriangle(e, px, py)) {
                            best = e;
                            bestSeq = e.seq;
                        }
                    }
                }
            }
            for (int i = oversized.size() - 1; i >= 0; i--) {
                final TriEntry e = oversized.get(i);
                if (e.seq >= bestSeq
                        || px < e.minX
                        || px > e.maxX
                        || py < e.minY
                        || py > e.maxY
                        || pointIndex == e.ia
                        || pointIndex == e.ib
                        || pointIndex == e.ic) {
                    continue;
                }
                if (exactPointInTriangle(e, px, py)) {
                    best = e;
                    bestSeq = e.seq;
                }
            }
            return best != null ? best.tri : null;
        }

        Object[] findIntersectingEdges(final ArrayList<?> constraintList) throws Throwable {
            final int ceCount = constraintList.size();
            EdgeAccess edgeAccess = null;
            PointAccess pointAccess = null;
            int outCount = 0;

            for (int c = 0; c < ceCount; c++) {
                final Object ce = constraintList.get(c);
                if (edgeAccess == null) {
                    edgeAccess = EDGE_ACCESS.get(ce.getClass());
                }
                final Object ceA = edgeAccess.a(ce);
                final Object ceB = edgeAccess.b(ce);
                if (pointAccess == null) {
                    pointAccess = POINT_ACCESS.get(ceA.getClass());
                }
                final float cax = pointAccess.getX(ceA);
                final float cay = pointAccess.getY(ceA);
                final int ceIa = pointAccess.getIndex(ceA);
                final float cbx = pointAccess.getX(ceB);
                final float cby = pointAccess.getY(ceB);
                final int ceIb = pointAccess.getIndex(ceB);
                final float pad = aabbPad(Math.abs(cbx - cax), Math.abs(cby - cay));
                final float ceMinX = Math.min(cax, cbx) - pad;
                final float ceMaxX = Math.max(cax, cbx) + pad;
                final float ceMinY = Math.min(cay, cby) - pad;
                final float ceMaxY = Math.max(cay, cby) + pad;

                final int candCount = collectIntersectingCandidates(cax, cay, cbx, cby, ceMinX, ceMaxX, ceMinY, ceMaxY);
                if (candCount == 0) {
                    continue;
                }
                if (candCount > 1) {
                    sortEntriesBySeq(candBuf, candCount);
                }

                for (int i = 0; i < candCount; i++) {
                    final TriEntry t = candBuf[i];
                    if (ceIa != t.ia
                            && ceIa != t.ib
                            && ceIb != t.ia
                            && ceIb != t.ib
                            && edgeOverlapsBox(t.ax, t.ay, t.bx, t.by, ceMinX, ceMaxX, ceMinY, ceMaxY)
                            && intersectsScalar(cax, cay, cbx, cby, t.ax, t.ay, t.bx, t.by)) {
                        outCount = appendPair(outCount, t.pa, t.pb);
                    }
                    if (ceIa != t.ib
                            && ceIa != t.ic
                            && ceIb != t.ib
                            && ceIb != t.ic
                            && edgeOverlapsBox(t.bx, t.by, t.cx, t.cy, ceMinX, ceMaxX, ceMinY, ceMaxY)
                            && intersectsScalar(cax, cay, cbx, cby, t.bx, t.by, t.cx, t.cy)) {
                        outCount = appendPair(outCount, t.pb, t.pc);
                    }
                    if (ceIa != t.ic
                            && ceIa != t.ia
                            && ceIb != t.ic
                            && ceIb != t.ia
                            && edgeOverlapsBox(t.cx, t.cy, t.ax, t.ay, ceMinX, ceMaxX, ceMinY, ceMaxY)
                            && intersectsScalar(cax, cay, cbx, cby, t.cx, t.cy, t.ax, t.ay)) {
                        outCount = appendPair(outCount, t.pc, t.pa);
                    }
                }
            }
            if (outCount == 0) {
                return EMPTY_OBJECTS;
            }
            return Arrays.copyOf(pairBuf, outCount);
        }

        private int appendPair(final int offset, final Object u, final Object v) {
            if (offset + 2 > pairBuf.length) {
                final int nextCap = Math.max(INITIAL_ENTRIES_CAPACITY, pairBuf.length * 2);
                pairBuf = pairBuf.length == 0 ? new Object[nextCap] : Arrays.copyOf(pairBuf, nextCap);
            }
            pairBuf[offset] = u;
            pairBuf[offset + 1] = v;
            return offset + 2;
        }

        private static boolean edgeOverlapsBox(
                final float x1,
                final float y1,
                final float x2,
                final float y2,
                final float boxMinX,
                final float boxMaxX,
                final float boxMinY,
                final float boxMaxY) {
            return Math.max(x1, x2) >= boxMinX
                    && Math.min(x1, x2) <= boxMaxX
                    && Math.max(y1, y2) >= boxMinY
                    && Math.min(y1, y2) <= boxMaxY;
        }

        private int collectIntersectingCandidates(
                final float ax,
                final float ay,
                final float bx,
                final float by,
                final float minX,
                final float maxX,
                final float minY,
                final float maxY) {
            int candCount = 0;
            if (!gridBuilt
                    || !Float.isFinite(minX)
                    || !Float.isFinite(maxX)
                    || !Float.isFinite(minY)
                    || !Float.isFinite(maxY)) {
                for (int i = 0; i < activeCount; i++) {
                    final TriEntry e = dense[i];
                    if (e.maxX >= minX && e.minX <= maxX && e.maxY >= minY && e.minY <= maxY) {
                        candCount = addCand(candCount, e);
                    }
                }
                return candCount;
            }

            final int stampId = nextStampId();
            final int gx0 = clamp((int) Math.floor((minX - originX) * invCellW), 0, gridW - 1);
            final int gx1 = clamp((int) Math.floor((maxX - originX) * invCellW), 0, gridW - 1);
            final int gy0 = clamp((int) Math.floor((minY - originY) * invCellH), 0, gridH - 1);
            final int gy1 = clamp((int) Math.floor((maxY - originY) * invCellH), 0, gridH - 1);
            final int cellArea = (gx1 - gx0 + 1) * (gy1 - gy0 + 1);
            if (cellArea <= SMALL_GRID_QUERY_CELL_LIMIT) {
                for (int gy = gy0; gy <= gy1; gy++) {
                    final int rowOffset = gy * gridW;
                    for (int gx = gx0; gx <= gx1; gx++) {
                        candCount = collectBucket(cells[rowOffset + gx], stampId, minX, maxX, minY, maxY, candCount);
                    }
                }
            } else {
                final int steps = Math.max(1, Math.max(gx1 - gx0, gy1 - gy0) * 2);
                for (int s = 0; s <= steps; s++) {
                    final float t = (float) s / (float) steps;
                    final float sx = ax + (bx - ax) * t;
                    final float sy = ay + (by - ay) * t;
                    final int cgx = clamp((int) Math.floor((sx - originX) * invCellW), gx0, gx1);
                    final int cgy = clamp((int) Math.floor((sy - originY) * invCellH), gy0, gy1);
                    final int yStart = Math.max(gy0, cgy - 1);
                    final int yEnd = Math.min(gy1, cgy + 1);
                    final int xStart = Math.max(gx0, cgx - 1);
                    final int xEnd = Math.min(gx1, cgx + 1);
                    for (int gy = yStart; gy <= yEnd; gy++) {
                        final int rowOffset = gy * gridW;
                        for (int gx = xStart; gx <= xEnd; gx++) {
                            candCount =
                                    collectBucket(cells[rowOffset + gx], stampId, minX, maxX, minY, maxY, candCount);
                        }
                    }
                }
            }
            for (int i = oversized.size() - 1; i >= 0; i--) {
                final TriEntry e = oversized.get(i);
                if (stamp[e.denseSlot] == stampId) {
                    continue;
                }
                stamp[e.denseSlot] = stampId;
                if (e.maxX >= minX && e.minX <= maxX && e.maxY >= minY && e.minY <= maxY) {
                    candCount = addCand(candCount, e);
                }
            }
            return candCount;
        }

        private int collectBucket(
                final ArrayList<TriEntry> bucket,
                final int stampId,
                final float minX,
                final float maxX,
                final float minY,
                final float maxY,
                int candCount) {
            if (bucket == null) {
                return candCount;
            }
            for (int i = bucket.size() - 1; i >= 0; i--) {
                final TriEntry e = bucket.get(i);
                if (stamp[e.denseSlot] == stampId) {
                    continue;
                }
                stamp[e.denseSlot] = stampId;
                if (e.maxX >= minX && e.minX <= maxX && e.maxY >= minY && e.minY <= maxY) {
                    candCount = addCand(candCount, e);
                }
            }
            return candCount;
        }

        private int addCand(final int candCount, final TriEntry e) {
            if (candCount == candBuf.length) {
                candBuf = Arrays.copyOf(candBuf, candBuf.length * 2);
            }
            candBuf[candCount] = e;
            return candCount + 1;
        }

        private int nextStampId() {
            if (++stampGen == 0) {
                Arrays.fill(stamp, 0);
                stampGen = 1;
            }
            return stampGen;
        }
    }

    private static final class EdgeSetState {
        WeakReference<ArrayList<?>> listRef;
        long[] directedKeys;
        long[] undirectedKeys;
        boolean hasZeroDirected;
        boolean hasZeroUndirected;
        int indexedSize;
        boolean dirty;
        long version;

        long gridVersion = -1L;
        int gridEdgeCount = -1;
        int edgeCount;
        Object[] edgeObjs = EMPTY_OBJECTS;
        float[] eaX = new float[0], eaY = new float[0], ebX = new float[0], ebY = new float[0];
        float[] minX = new float[0], maxX = new float[0], minY = new float[0], maxY = new float[0];
        int[] eaIdx = new int[0], ebIdx = new int[0];
        boolean edgeGridBuilt;
        float gridOriginX, gridOriginY, gridInvW, gridInvH;
        int gridW, gridH;
        ArrayList<Integer>[] edgeCells;
        final ArrayList<Integer> oversizedEdges = new ArrayList<>();
        int[] edgeStamp = new int[INITIAL_ENTRIES_CAPACITY];
        int edgeStampGen;
        int[] hitIndices = new int[INITIAL_HASH_CAPACITY];

        @SuppressWarnings("ReferenceEquality") // Avoids O(N) AbstractList.equals traversal on every edge update.
        void bindList(final ArrayList<?> list) {
            if (list != null && (listRef == null || listRef.get() != list)) {
                listRef = new WeakReference<>(list);
            }
        }

        ArrayList<?> resolveList(final Object edgeSet) throws Throwable {
            final ArrayList<?> cached = listRef != null ? listRef.get() : null;
            if (cached != null) {
                return cached;
            }
            final Object raw = EDGE_LIST_ACCESS.get(edgeSet.getClass()).invokeExact(edgeSet);
            final ArrayList<?> resolved = (ArrayList<?>) raw;
            bindList(resolved);
            return resolved;
        }

        void rebuildHashFromList(final ArrayList<?> list) throws Throwable {
            final int size = list.size();
            final int cap = tableCapacityFor(size);
            directedKeys = new long[cap];
            undirectedKeys = new long[cap];
            hasZeroDirected = false;
            hasZeroUndirected = false;
            EdgeAccess edgeAccess = null;
            PointAccess pointAccess = null;
            for (int i = 0; i < size; i++) {
                final Object edge = list.get(i);
                if (edgeAccess == null) {
                    edgeAccess = EDGE_ACCESS.get(edge.getClass());
                }
                final Object pa = edgeAccess.a(edge);
                final Object pb = edgeAccess.b(edge);
                if (pointAccess == null) {
                    pointAccess = POINT_ACCESS.get(pa.getClass());
                }
                insertEdgeKeyNoGrow(pointAccess.getIndex(pa), pointAccess.getIndex(pb));
            }
            indexedSize = size;
            dirty = false;
        }

        void insertEdgeKey(final int ia, final int ib) {
            if (directedKeys == null) {
                directedKeys = new long[INITIAL_HASH_CAPACITY];
                undirectedKeys = new long[INITIAL_HASH_CAPACITY];
            } else if ((indexedSize + 1) * HASH_LOAD_DIVISOR >= directedKeys.length) {
                rehashBoth(directedKeys.length * 2);
            }
            insertEdgeKeyNoGrow(ia, ib);
        }

        private void insertEdgeKeyNoGrow(final int ia, final int ib) {
            final long dirKey = (((long) ia) << INT_MASK_SHIFT) | (ib & LOW_INT_MASK);
            if (dirKey == 0L) {
                hasZeroDirected = true;
            } else {
                insertNonZero(directedKeys, dirKey);
            }
            final int lo = Math.min(ia, ib);
            final int hi = Math.max(ia, ib);
            final long undirKey = (((long) lo) << INT_MASK_SHIFT) | (hi & LOW_INT_MASK);
            if (undirKey == 0L) {
                hasZeroUndirected = true;
            } else {
                insertNonZero(undirectedKeys, undirKey);
            }
        }

        boolean contains(final int ia, final int ib, final boolean directed) {
            if (directed) {
                final long key = (((long) ia) << INT_MASK_SHIFT) | (ib & LOW_INT_MASK);
                return key == 0L ? hasZeroDirected : containsNonZero(directedKeys, key);
            }
            final int lo = Math.min(ia, ib);
            final int hi = Math.max(ia, ib);
            final long key = (((long) lo) << INT_MASK_SHIFT) | (hi & LOW_INT_MASK);
            return key == 0L ? hasZeroUndirected : containsNonZero(undirectedKeys, key);
        }

        private void rehashBoth(final int newCap) {
            final long[] nextDir = new long[newCap];
            for (final long key : directedKeys) {
                if (key != 0L) {
                    insertNonZero(nextDir, key);
                }
            }
            final long[] nextUndir = new long[newCap];
            for (final long key : undirectedKeys) {
                if (key != 0L) {
                    insertNonZero(nextUndir, key);
                }
            }
            directedKeys = nextDir;
            undirectedKeys = nextUndir;
        }

        private static int tableCapacityFor(final int size) {
            int cap = INITIAL_HASH_CAPACITY;
            while (size * HASH_LOAD_DIVISOR >= cap) {
                cap *= 2;
            }
            return cap;
        }

        private static int mixSlot(final long key, final int mask) {
            final long mixed = key * HASH_MIX_CONSTANT;
            return ((int) (mixed ^ (mixed >>> INT_MASK_SHIFT))) & mask;
        }

        private static void insertNonZero(final long[] table, final long key) {
            final int mask = table.length - 1;
            int slot = mixSlot(key, mask);
            while (true) {
                final long existing = table[slot];
                if (existing == 0L || existing == key) {
                    table[slot] = key;
                    return;
                }
                slot = (slot + 1) & mask;
            }
        }

        private static boolean containsNonZero(final long[] table, final long key) {
            if (table == null) {
                return false;
            }
            final int mask = table.length - 1;
            int slot = mixSlot(key, mask);
            while (true) {
                final long existing = table[slot];
                if (existing == 0L) {
                    return false;
                }
                if (existing == key) {
                    return true;
                }
                slot = (slot + 1) & mask;
            }
        }

        @SuppressWarnings("unchecked")
        void ensureEdgeGrid(final ArrayList<?> list) throws Throwable {
            final int size = list.size();
            if (gridVersion == version && gridEdgeCount == size && edgeCount == size) {
                return;
            }
            edgeCount = size;
            if (edgeObjs.length < size) {
                final int nextCap = Math.max(size, INITIAL_ENTRIES_CAPACITY);
                edgeObjs = new Object[nextCap];
                eaX = new float[nextCap];
                eaY = new float[nextCap];
                ebX = new float[nextCap];
                ebY = new float[nextCap];
                minX = new float[nextCap];
                maxX = new float[nextCap];
                minY = new float[nextCap];
                maxY = new float[nextCap];
                eaIdx = new int[nextCap];
                ebIdx = new int[nextCap];
                edgeStamp = new int[nextCap];
            }
            EdgeAccess edgeAccess = null;
            PointAccess pointAccess = null;
            for (int i = 0; i < size; i++) {
                final Object edge = list.get(i);
                if (edgeAccess == null) {
                    edgeAccess = EDGE_ACCESS.get(edge.getClass());
                }
                final Object pa = edgeAccess.a(edge);
                final Object pb = edgeAccess.b(edge);
                if (pointAccess == null) {
                    pointAccess = POINT_ACCESS.get(pa.getClass());
                }
                final float ax = pointAccess.getX(pa);
                final float ay = pointAccess.getY(pa);
                final float bx = pointAccess.getX(pb);
                final float by = pointAccess.getY(pb);
                final float absDx = Math.abs(bx - ax);
                final float absDy = Math.abs(by - ay);
                final float pad = aabbPad(absDx, absDy);
                edgeObjs[i] = edge;
                eaX[i] = ax;
                eaY[i] = ay;
                ebX[i] = bx;
                ebY[i] = by;
                eaIdx[i] = pointAccess.getIndex(pa);
                ebIdx[i] = pointAccess.getIndex(pb);
                // Symmetric bounds cover both forward projections and Cubism's +PI wrapped -180deg sector.
                minX[i] = ax - absDx - pad;
                maxX[i] = ax + absDx + pad;
                minY[i] = ay - absDy - pad;
                maxY[i] = ay + absDy + pad;
            }
            edgeGridBuilt = false;
            oversizedEdges.clear();
            if (edgeCells != null) {
                Arrays.fill(edgeCells, null);
            }
            if (size >= MIN_EDGES_FOR_GRID) {
                buildEdgeSpatialGrid(size);
            }
            gridVersion = version;
            gridEdgeCount = size;
        }

        @SuppressWarnings("unchecked")
        private void buildEdgeSpatialGrid(final int size) {
            final int step = Math.max(1, size / GRID_SAMPLE_LIMIT);
            final int maxSamples = Math.min(GRID_SAMPLE_LIMIT, size);
            final float[] widths = new float[maxSamples];
            final float[] heights = new float[maxSamples];
            int samples = 0;
            for (int i = 0; i < size && samples < maxSamples; i += step) {
                if (!Float.isFinite(minX[i])
                        || !Float.isFinite(maxX[i])
                        || !Float.isFinite(minY[i])
                        || !Float.isFinite(maxY[i])) {
                    continue;
                }
                widths[samples] = maxX[i] - minX[i];
                heights[samples] = maxY[i] - minY[i];
                samples++;
            }
            if (samples == 0) {
                return;
            }
            Arrays.sort(widths, 0, samples);
            Arrays.sort(heights, 0, samples);
            final float p75W = quantile(widths, samples, PERCENTILE_NUMERATOR, PERCENTILE_DENOMINATOR);
            final float p75H = quantile(heights, samples, PERCENTILE_NUMERATOR, PERCENTILE_DENOMINATOR);
            final float outlierW = Math.max(p75W * OUTLIER_SPAN_FACTOR, MIN_CELL_SPAN * OUTLIER_SPAN_FACTOR);
            final float outlierH = Math.max(p75H * OUTLIER_SPAN_FACTOR, MIN_CELL_SPAN * OUTLIER_SPAN_FACTOR);

            float gMinX = Float.POSITIVE_INFINITY;
            float gMaxX = Float.NEGATIVE_INFINITY;
            float gMinY = Float.POSITIVE_INFINITY;
            float gMaxY = Float.NEGATIVE_INFINITY;
            int coreCount = 0;
            for (int i = 0; i < size; i++) {
                if (!Float.isFinite(minX[i])
                        || !Float.isFinite(maxX[i])
                        || !Float.isFinite(minY[i])
                        || !Float.isFinite(maxY[i])) {
                    continue;
                }
                if (maxX[i] - minX[i] <= outlierW && maxY[i] - minY[i] <= outlierH) {
                    gMinX = Math.min(gMinX, minX[i]);
                    gMaxX = Math.max(gMaxX, maxX[i]);
                    gMinY = Math.min(gMinY, minY[i]);
                    gMaxY = Math.max(gMaxY, maxY[i]);
                    coreCount++;
                }
            }
            if (coreCount * 2 < size) {
                return;
            }
            final float totalW = Math.max(gMaxX - gMinX, MIN_CELL_SPAN);
            final float totalH = Math.max(gMaxY - gMinY, MIN_CELL_SPAN);
            float cellW = Math.max(p75W * CELL_SCALE_PADDING, Math.max(totalW / MAX_GRID_DIMENSION, MIN_CELL_SPAN));
            float cellH = Math.max(p75H * CELL_SCALE_PADDING, Math.max(totalH / MAX_GRID_DIMENSION, MIN_CELL_SPAN));
            int gw = clamp((int) Math.ceil(totalW / cellW), 1, MAX_GRID_DIMENSION);
            int gh = clamp((int) Math.ceil(totalH / cellH), 1, MAX_GRID_DIMENSION);
            if (gw * gh > MAX_TOTAL_CELLS) {
                final double scale = Math.sqrt((double) (gw * gh) / (double) MAX_TOTAL_CELLS);
                cellW *= (float) scale;
                cellH *= (float) scale;
                gw = clamp((int) Math.ceil(totalW / cellW), 1, MAX_GRID_DIMENSION);
                gh = clamp((int) Math.ceil(totalH / cellH), 1, MAX_GRID_DIMENSION);
            }
            final int totalCells = gw * gh;
            if (edgeCells == null || edgeCells.length < totalCells) {
                edgeCells = new ArrayList[totalCells];
            }
            gridOriginX = gMinX;
            gridOriginY = gMinY;
            gridInvW = 1.0f / cellW;
            gridInvH = 1.0f / cellH;
            gridW = gw;
            gridH = gh;
            edgeGridBuilt = true;

            for (int i = 0; i < size; i++) {
                if (!Float.isFinite(minX[i])
                        || !Float.isFinite(maxX[i])
                        || !Float.isFinite(minY[i])
                        || !Float.isFinite(maxY[i])) {
                    oversizedEdges.add(i);
                    continue;
                }
                final int gx0 = (int) Math.floor((minX[i] - gridOriginX) * gridInvW);
                final int gx1 = (int) Math.floor((maxX[i] - gridOriginX) * gridInvW);
                final int gy0 = (int) Math.floor((minY[i] - gridOriginY) * gridInvH);
                final int gy1 = (int) Math.floor((maxY[i] - gridOriginY) * gridInvH);
                if (gx0 < 0
                        || gx1 >= gw
                        || gy0 < 0
                        || gy1 >= gh
                        || (gx1 - gx0 + 1) * (gy1 - gy0 + 1) > MAX_CELLS_PER_ITEM) {
                    oversizedEdges.add(i);
                    continue;
                }
                for (int gy = gy0; gy <= gy1; gy++) {
                    final int rowOffset = gy * gw;
                    for (int gx = gx0; gx <= gx1; gx++) {
                        final int cellIdx = rowOffset + gx;
                        ArrayList<Integer> bucket = edgeCells[cellIdx];
                        if (bucket == null) {
                            bucket = new ArrayList<>(4);
                            edgeCells[cellIdx] = bucket;
                        }
                        bucket.add(i);
                    }
                }
            }
        }

        Iterator<Object> queryCollinearCandidates(final float px, final float py, final int pointIndex) {
            if (!edgeGridBuilt) {
                ArrayList<Object> hits = null;
                for (int i = 0; i < edgeCount; i++) {
                    if (passesScalarEdgeFilter(i, px, py, pointIndex)) {
                        if (hits == null) {
                            hits = new ArrayList<>(2);
                        }
                        hits.add(edgeObjs[i]);
                    }
                }
                return hits == null ? null : hits.iterator();
            }

            int hitCount = 0;
            final int stampId = nextEdgeStamp();
            final int gx = (int) Math.floor((px - gridOriginX) * gridInvW);
            final int gy = (int) Math.floor((py - gridOriginY) * gridInvH);
            if (gx >= 0 && gx < gridW && gy >= 0 && gy < gridH) {
                final ArrayList<Integer> bucket = edgeCells[gy * gridW + gx];
                if (bucket != null) {
                    for (int i = bucket.size() - 1; i >= 0; i--) {
                        final int idx = bucket.get(i);
                        if (edgeStamp[idx] == stampId) {
                            continue;
                        }
                        edgeStamp[idx] = stampId;
                        if (passesScalarEdgeFilter(idx, px, py, pointIndex)) {
                            hitCount = recordHit(hitCount, idx);
                        }
                    }
                }
            }
            for (int i = oversizedEdges.size() - 1; i >= 0; i--) {
                final int idx = oversizedEdges.get(i);
                if (edgeStamp[idx] == stampId) {
                    continue;
                }
                edgeStamp[idx] = stampId;
                if (passesScalarEdgeFilter(idx, px, py, pointIndex)) {
                    hitCount = recordHit(hitCount, idx);
                }
            }
            if (hitCount == 0) {
                return null;
            }
            if (hitCount == 1) {
                return Collections.singletonList(edgeObjs[hitIndices[0]]).iterator();
            }
            sortInts(hitIndices, hitCount);
            final ArrayList<Object> hits = new ArrayList<>(hitCount);
            for (int i = 0; i < hitCount; i++) {
                hits.add(edgeObjs[hitIndices[i]]);
            }
            return hits.iterator();
        }

        private int recordHit(final int hitCount, final int edgeIndex) {
            if (hitCount == hitIndices.length) {
                hitIndices = Arrays.copyOf(hitIndices, hitIndices.length * 2);
            }
            hitIndices[hitCount] = edgeIndex;
            return hitCount + 1;
        }

        private int nextEdgeStamp() {
            if (++edgeStampGen == 0) {
                Arrays.fill(edgeStamp, 0);
                edgeStampGen = 1;
            }
            return edgeStampGen;
        }

        private boolean passesScalarEdgeFilter(final int idx, final float px, final float py, final int pointIndex) {
            if (pointIndex == eaIdx[idx] || pointIndex == ebIdx[idx]) {
                return false;
            }
            if (px < minX[idx] || px > maxX[idx] || py < minY[idx] || py > maxY[idx]) {
                return false;
            }
            final float ax = eaX[idx];
            final float ay = eaY[idx];
            final float dx1 = ebX[idx] - ax;
            final float dy1 = ebY[idx] - ay;
            final float dx2 = px - ax;
            final float dy2 = py - ay;
            final float cross = dx1 * dy2 - dy1 * dx2;
            final float dot = dx1 * dx2 + dy1 * dy2;
            return !rejectCollinear(cross, dot);
        }
    }

    private static final class ContourData {
        final float minX, maxX, minY, maxY;
        final float[] segMinX, segMaxX, segMinY, segMaxY;
        final float[] upX, upY, downX, downY;
        final int[] slabOffsets, slabCounts, slabEdges;
        final float slabOriginY, slabInvH;
        final boolean hasSlabs;

        private ContourData(
                final float minX,
                final float maxX,
                final float minY,
                final float maxY,
                final float[] segMinX,
                final float[] segMaxX,
                final float[] segMinY,
                final float[] segMaxY,
                final float[] upX,
                final float[] upY,
                final float[] downX,
                final float[] downY,
                final int[] slabOffsets,
                final int[] slabCounts,
                final int[] slabEdges,
                final float slabOriginY,
                final float slabInvH) {
            this.minX = minX;
            this.maxX = maxX;
            this.minY = minY;
            this.maxY = maxY;
            this.segMinX = segMinX;
            this.segMaxX = segMaxX;
            this.segMinY = segMinY;
            this.segMaxY = segMaxY;
            this.upX = upX;
            this.upY = upY;
            this.downX = downX;
            this.downY = downY;
            this.slabOffsets = slabOffsets;
            this.slabCounts = slabCounts;
            this.slabEdges = slabEdges;
            this.slabOriginY = slabOriginY;
            this.slabInvH = slabInvH;
            this.hasSlabs = slabEdges != null;
        }

        static ContourData compile(final int[] indices, final float[] px, final float[] py) {
            final int n = indices.length;
            if (n == 0) {
                return new ContourData(
                        Float.POSITIVE_INFINITY,
                        Float.NEGATIVE_INFINITY,
                        Float.POSITIVE_INFINITY,
                        Float.NEGATIVE_INFINITY,
                        new float[0],
                        new float[0],
                        new float[0],
                        new float[0],
                        new float[0],
                        new float[0],
                        new float[0],
                        new float[0],
                        null,
                        null,
                        null,
                        0.0f,
                        0.0f);
            }
            final float[] segMinX = new float[n];
            final float[] segMaxX = new float[n];
            final float[] segMinY = new float[n];
            final float[] segMaxY = new float[n];
            final float[] upX = new float[n];
            final float[] upY = new float[n];
            final float[] downX = new float[n];
            final float[] downY = new float[n];
            float cMinX = Float.POSITIVE_INFINITY;
            float cMaxX = Float.NEGATIVE_INFINITY;
            float cMinY = Float.POSITIVE_INFINITY;
            float cMaxY = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                final int i1 = indices[i];
                final int i2 = indices[(i + 1) % n];
                final float x1 = px[i1];
                final float y1 = py[i1];
                final float x2 = px[i2];
                final float y2 = py[i2];
                cMinX = Math.min(cMinX, x1);
                cMaxX = Math.max(cMaxX, x1);
                cMinY = Math.min(cMinY, y1);
                cMaxY = Math.max(cMaxY, y1);
                segMinX[i] = Math.min(x1, x2);
                segMaxX[i] = Math.max(x1, x2);
                segMinY[i] = Math.min(y1, y2);
                segMaxY[i] = Math.max(y1, y2);
                if (y1 > y2) {
                    upX[i] = x1;
                    upY[i] = y1;
                    downX[i] = x2;
                    downY[i] = y2;
                } else {
                    upX[i] = x2;
                    upY[i] = y2;
                    downX[i] = x1;
                    downY[i] = y1;
                }
            }
            final float pad = aabbPad(cMaxX - cMinX, cMaxY - cMinY);
            final float paddedMinX = Float.isFinite(cMinX) ? cMinX - pad : Float.NEGATIVE_INFINITY;
            final float paddedMaxX = Float.isFinite(cMaxX) ? cMaxX + pad : Float.POSITIVE_INFINITY;
            final float paddedMinY = Float.isFinite(cMinY) ? cMinY - pad : Float.NEGATIVE_INFINITY;
            final float paddedMaxY = Float.isFinite(cMaxY) ? cMaxY + pad : Float.POSITIVE_INFINITY;

            int[] slabOffsets = null;
            int[] slabCounts = null;
            int[] slabEdges = null;
            float slabOriginY = 0.0f;
            float slabInvH = 0.0f;
            final float spanY = cMaxY - cMinY;
            if (n >= CONTOUR_SLAB_MIN_EDGES && Float.isFinite(spanY) && spanY > MIN_CELL_SPAN) {
                slabOriginY = cMinY;
                slabInvH = (float) CONTOUR_SLAB_COUNT / spanY;
                slabCounts = new int[CONTOUR_SLAB_COUNT];
                int totalRefs = 0;
                for (int i = 0; i < n; i++) {
                    final int s0 =
                            clamp((int) Math.floor((segMinY[i] - slabOriginY) * slabInvH), 0, CONTOUR_SLAB_COUNT - 1);
                    final int s1 =
                            clamp((int) Math.floor((segMaxY[i] - slabOriginY) * slabInvH), 0, CONTOUR_SLAB_COUNT - 1);
                    for (int s = s0; s <= s1; s++) {
                        slabCounts[s]++;
                        totalRefs++;
                    }
                }
                slabOffsets = new int[CONTOUR_SLAB_COUNT];
                final int[] cursors = new int[CONTOUR_SLAB_COUNT];
                int offset = 0;
                for (int s = 0; s < CONTOUR_SLAB_COUNT; s++) {
                    slabOffsets[s] = offset;
                    cursors[s] = offset;
                    offset += slabCounts[s];
                }
                slabEdges = new int[totalRefs];
                for (int i = 0; i < n; i++) {
                    final int s0 =
                            clamp((int) Math.floor((segMinY[i] - slabOriginY) * slabInvH), 0, CONTOUR_SLAB_COUNT - 1);
                    final int s1 =
                            clamp((int) Math.floor((segMaxY[i] - slabOriginY) * slabInvH), 0, CONTOUR_SLAB_COUNT - 1);
                    for (int s = s0; s <= s1; s++) {
                        slabEdges[cursors[s]++] = i;
                    }
                }
            }
            return new ContourData(
                    paddedMinX,
                    paddedMaxX,
                    paddedMinY,
                    paddedMaxY,
                    segMinX,
                    segMaxX,
                    segMinY,
                    segMaxY,
                    upX,
                    upY,
                    downX,
                    downY,
                    slabOffsets,
                    slabCounts,
                    slabEdges,
                    slabOriginY,
                    slabInvH);
        }

        boolean contains(final float cx, final float cy) {
            if (cx < minX || cx > maxX || cy < minY || cy > maxY) {
                return false;
            }
            int crossings = 0;
            if (hasSlabs) {
                final int slab = clamp((int) Math.floor((cy - slabOriginY) * slabInvH), 0, CONTOUR_SLAB_COUNT - 1);
                final int start = slabOffsets[slab];
                final int end = start + slabCounts[slab];
                for (int k = start; k < end; k++) {
                    crossings += edgeCrossing(slabEdges[k], cx, cy);
                }
            } else {
                final int n = segMaxX.length;
                for (int i = 0; i < n; i++) {
                    crossings += edgeCrossing(i, cx, cy);
                }
            }
            return (crossings & 1) != 0;
        }

        private int edgeCrossing(final int i, final float cx, final float cy) {
            if (cx > segMaxX[i]) {
                return 0;
            }
            if (cy < segMinY[i] || cy >= segMaxY[i]) {
                return 0;
            }
            final float ux = upX[i];
            final float uy = upY[i];
            final float cross = (downX[i] - ux) * (cy - uy) - (downY[i] - uy) * (cx - ux);
            return cross < 0.0f ? 1 : 0;
        }
    }

    /** Weak identity registry pairing host solver instances with per-instance auxiliary state. */
    private static final class WeakIdentityMap<V> {
        private final ConcurrentHashMap<Key, V> entries = new ConcurrentHashMap<>();
        private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
        private final ThreadLocal<Lookup> probe = ThreadLocal.withInitial(Lookup::new);

        V peek(final Object host) {
            drain();
            final Lookup lookup = probe.get();
            lookup.target = host;
            lookup.hash = System.identityHashCode(host);
            try {
                return entries.get(lookup);
            } finally {
                lookup.target = null;
            }
        }

        V getOrCreate(final Object host, final java.util.function.Supplier<V> factory) {
            final V existing = peek(host);
            if (existing != null) {
                return existing;
            }
            final Key key = new WeakKey(host, queue);
            final V created = factory.get();
            final V raced = entries.putIfAbsent(key, created);
            return raced != null ? raced : created;
        }

        void invalidate(final Object host) {
            if (host == null) {
                return;
            }
            drain();
            final Lookup lookup = probe.get();
            lookup.target = host;
            lookup.hash = System.identityHashCode(host);
            try {
                entries.remove(lookup);
            } finally {
                lookup.target = null;
            }
        }

        private void drain() {
            WeakKey dead;
            while ((dead = (WeakKey) queue.poll()) != null) {
                entries.remove(dead);
            }
        }

        private interface Key {
            Object target();

            int hash();
        }

        private static final class Lookup implements Key {
            Object target;
            int hash;

            @Override
            public Object target() {
                return target;
            }

            @Override
            public int hash() {
                return hash;
            }

            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public boolean equals(final Object other) {
                return other instanceof Key key && target == key.target();
            }
        }

        private static final class WeakKey extends WeakReference<Object> implements Key {
            final int hash;

            WeakKey(final Object referent, final ReferenceQueue<Object> queue) {
                super(referent, queue);
                this.hash = System.identityHashCode(referent);
            }

            @Override
            public Object target() {
                return get();
            }

            @Override
            public int hash() {
                return hash;
            }

            @Override
            public int hashCode() {
                return hash;
            }

            @Override
            public boolean equals(final Object other) {
                if (this == other) {
                    return true;
                }
                final Object self = get();
                return self != null && other instanceof Key key && self == key.target();
            }
        }
    }
}
