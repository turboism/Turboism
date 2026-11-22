package dev.turboism.adapter.cubism.meshedit;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;

/**
 * Zero-allocation point-in-triangle lookup and cached uniform triangle grid matching Cubism float
 * and double containment semantics.
 */
public final class PointInTriangleAccelerator {

    /** Returned to bytecode bridges when no enclosing triangle exists under non-fallback parity. */
    public static final Object NULL_SENTINEL = new Object();

    private static final int TRI_INDICES_STRIDE = 3;
    private static final int MIN_STEP = 2;
    private static final int SENTINEL_INDEX = -1;
    private static final int MIN_TRIS_FOR_GRID = 24;
    private static final int MAX_CELLS_PER_TRIANGLE = 32;
    private static final int THREAD_CACHE_SLOTS = 4;
    private static final int FALLBACK_STATUS = -1;
    private static final int TRUE_STATUS = 1;
    private static final int FALSE_STATUS = 0;
    private static final double MIN_CELL_SPAN = 1.0e-5;
    private static final double AABB_MARGIN_RATIO = 1.0e-4;
    private static final double AABB_MIN_MARGIN = 1.0e-5;

    private static final String POINT_IN_TRIANGLE_D_CLASS =
            "com.live2d.cubism.doc.model.drawable.artMesh.PointInTriangleD";
    private static final String POINT_IN_TRIANGLE_CLASS =
            "com.live2d.cubism.doc.model.drawable.artMesh.PointInTriangle";

    private static final ClassValue<VectorAccess> VECTOR_ACCESS = new ClassValue<>() {
        @Override
        protected VectorAccess computeValue(final Class<?> type) {
            return VectorAccess.resolve(type);
        }
    };
    private static final ClassValue<MethodHandle> DOUBLE_RESULT_CTOR = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(final Class<?> hostClass) {
            return resolveResultCtor(
                    hostClass.getClassLoader(),
                    POINT_IN_TRIANGLE_D_CLASS,
                    MethodType.methodType(
                            void.class, int.class, double.class, int.class, double.class, int.class, double.class));
        }
    };
    private static final ClassValue<MethodHandle> FLOAT_RESULT_CTOR = new ClassValue<>() {
        @Override
        protected MethodHandle computeValue(final Class<?> hostClass) {
            return resolveResultCtor(
                    hostClass.getClassLoader(),
                    POINT_IN_TRIANGLE_CLASS,
                    MethodType.methodType(
                            void.class, int.class, float.class, int.class, float.class, int.class, float.class));
        }
    };

    private static final ThreadLocal<GridCacheEntry[]> GRID_CACHE =
            ThreadLocal.withInitial(() -> new GridCacheEntry[THREAD_CACHE_SLOTS]);

    private PointInTriangleAccelerator() {}

    /**
     * Resolves an enclosing triangle and double-precision barycentric coordinates without temporary
     * vector allocations.
     *
     * <p>Returns {@code null} when callers must run the native host bytecode (for degenerate logger
     * side effects or invalid index bounds). Returns {@link #NULL_SENTINEL} when no match exists.
     *
     * @param queryPoint query point ({@code GVector2})
     * @param positions flat vertex coordinates
     * @param indices triangle vertex index triples
     * @param step coordinate stride per vertex
     * @param skipFallback skips minimum-weight nearest triangle search when true
     * @return {@code PointInTriangleD}, {@link #NULL_SENTINEL}, or {@code null} for fallback
     */
    public static Object findDouble(
            final Object queryPoint,
            final float[] positions,
            final int[] indices,
            final int step,
            final boolean skipFallback) {
        if (queryPoint == null || positions == null || indices == null || step < MIN_STEP) {
            return null;
        }
        try {
            final MethodHandle ctor = DOUBLE_RESULT_CTOR.get(queryPoint.getClass());
            if (ctor == null) {
                return null;
            }
            final VectorAccess vec = VECTOR_ACCESS.get(queryPoint.getClass());
            final float px = (float) vec.getX.invoke(queryPoint);
            final float py = (float) vec.getY.invoke(queryPoint);
            return findDoubleCoords(px, py, positions, indices, step, skipFallback, ctor);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Resolves an enclosing triangle and float-precision barycentric coordinates without temporary
     * vector allocations.
     *
     * <p>Returns {@code null} when callers must run the native host bytecode. Returns
     * {@link #NULL_SENTINEL} when no match exists.
     *
     * @param queryPoint query point ({@code GVector2})
     * @param positions flat vertex coordinates
     * @param indices triangle vertex index triples
     * @param step coordinate stride per vertex
     * @param skipFallback skips minimum-weight nearest triangle search when true
     * @return {@code PointInTriangle}, {@link #NULL_SENTINEL}, or {@code null} for fallback
     */
    public static Object findFloat(
            final Object queryPoint,
            final float[] positions,
            final int[] indices,
            final int step,
            final boolean skipFallback) {
        if (queryPoint == null || positions == null || indices == null || step < MIN_STEP) {
            return null;
        }
        try {
            final MethodHandle ctor = FLOAT_RESULT_CTOR.get(queryPoint.getClass());
            if (ctor == null) {
                return null;
            }
            final VectorAccess vec = VECTOR_ACCESS.get(queryPoint.getClass());
            final float px = (float) vec.getX.invoke(queryPoint);
            final float py = (float) vec.getY.invoke(queryPoint);
            return findFloatCoords(px, py, positions, indices, step, skipFallback, ctor);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    /**
     * Locates the first strictly containing triangle matching {@code MeshUtils.a} float sign rules
     * and writes its vertex indices into {@code outTriangle} when provided.
     *
     * @param positions flat vertex coordinates
     * @param indices triangle index triples
     * @param step coordinate stride per vertex
     * @param queryPoint query point ({@code GVector2})
     * @param outTriangle optional 3-element output array receiving matched vertex indices
     * @return {@code 1} when contained, {@code 0} when outside, or {@code -1} to run host fallback
     */
    public static int findStrictTriangle(
            final float[] positions,
            final int[] indices,
            final int step,
            final Object queryPoint,
            final int[] outTriangle) {
        if (positions == null || indices == null || queryPoint == null || step < MIN_STEP) {
            return FALLBACK_STATUS;
        }
        try {
            final VectorAccess vec = VECTOR_ACCESS.get(queryPoint.getClass());
            final float px = (float) vec.getX.invoke(queryPoint);
            final float py = (float) vec.getY.invoke(queryPoint);
            final int triCount = indices.length / TRI_INDICES_STRIDE;
            final TriangleGrid grid = triCount >= MIN_TRIS_FOR_GRID ? resolveGrid(positions, indices, step) : null;
            final int hitTri = grid != null
                    ? queryFirstTriangle(px, py, positions, indices, step, grid, true)
                    : scanFirstTriangle(px, py, positions, indices, step, triCount, true);
            if (hitTri == Integer.MIN_VALUE) {
                return FALLBACK_STATUS;
            }
            if (hitTri < 0) {
                return FALSE_STATUS;
            }
            if (outTriangle != null) {
                final int base = hitTri * TRI_INDICES_STRIDE;
                outTriangle[0] = indices[base];
                outTriangle[1] = indices[base + 1];
                outTriangle[2] = indices[base + 2];
            }
            return TRUE_STATUS;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return FALLBACK_STATUS;
        }
    }

    /**
     * Transforms a packed coordinate array across two mesh states using a single shared grid lookup
     * per vertex.
     *
     * @param srcPositions source mesh vertices
     * @param dstPositions target mesh vertices
     * @param indices triangle indices
     * @param step coordinate stride
     * @param inputs input flat {@code x,y} array
     * @param outputs output flat {@code x,y} array
     * @return populated {@code outputs} or {@code null} when caller fallback is needed
     */
    public static float[] tryTransformBatch(
            final float[] srcPositions,
            final float[] dstPositions,
            final int[] indices,
            final int step,
            final float[] inputs,
            final float[] outputs) {
        if (srcPositions == null
                || dstPositions == null
                || indices == null
                || inputs == null
                || outputs == null
                || step < MIN_STEP
                || inputs.length != outputs.length
                || (inputs.length & 1) != 0) {
            return null;
        }
        final int pointCount = srcPositions.length / step;
        if (pointCount < TRI_INDICES_STRIDE) {
            return null;
        }
        final int triCount = indices.length / TRI_INDICES_STRIDE;
        final TriangleGrid grid = triCount >= MIN_TRIS_FOR_GRID ? resolveGrid(srcPositions, indices, step) : null;
        if (triCount >= MIN_TRIS_FOR_GRID && grid == null) {
            return null;
        }
        final int queryCount = inputs.length >>> 1;
        final double[] weights = new double[TRI_INDICES_STRIDE];
        for (int q = 0; q < queryCount; q++) {
            final int qOff = q << 1;
            final float px = inputs[qOff];
            final float py = inputs[qOff + 1];
            final int hit = grid != null
                    ? queryFirstTriangle(px, py, srcPositions, indices, step, grid, false)
                    : scanFirstTriangle(px, py, srcPositions, indices, step, triCount, false);
            if (hit == Integer.MIN_VALUE) {
                return null;
            }
            final int i0;
            final int i1;
            final int i2;
            if (hit >= 0) {
                final int base = hit * TRI_INDICES_STRIDE;
                i0 = indices[base];
                i1 = indices[base + 1];
                i2 = indices[base + 2];
                final int p0 = i0 * step;
                final int p1 = i1 * step;
                final int p2 = i2 * step;
                if (!computeBarycentricDouble(
                        px,
                        py,
                        srcPositions[p0],
                        srcPositions[p0 + 1],
                        srcPositions[p1],
                        srcPositions[p1 + 1],
                        srcPositions[p2],
                        srcPositions[p2 + 1],
                        weights)) {
                    return null;
                }
            } else {
                final int bestTri = findNearestWeightTriangleDouble(
                        px, py, srcPositions, indices, step, triCount, weights);
                if (bestTri == Integer.MIN_VALUE) {
                    return null;
                }
                if (bestTri < 0) {
                    return null;
                }
                final int base = bestTri * TRI_INDICES_STRIDE;
                i0 = indices[base];
                i1 = indices[base + 1];
                i2 = indices[base + 2];
                if (!validateVertexOffset(dstPositions, i0, step)
                        || !validateVertexOffset(dstPositions, i1, step)
                        || !validateVertexOffset(dstPositions, i2, step)) {
                    return null;
                }
            }
            final int d0 = i0 * step;
            final int d1 = i1 * step;
            final int d2 = i2 * step;
            if (d0 + 1 >= dstPositions.length || d1 + 1 >= dstPositions.length || d2 + 1 >= dstPositions.length) {
                return null;
            }
            final float x = (float) (dstPositions[d0] * weights[0]
                    + dstPositions[d1] * weights[1]
                    + dstPositions[d2] * weights[2]);
            final float y = (float) (dstPositions[d0 + 1] * weights[0]
                    + dstPositions[d1 + 1] * weights[1]
                    + dstPositions[d2 + 1] * weights[2]);
            outputs[qOff] = x;
            outputs[qOff + 1] = y;
        }
        return outputs;
    }

    private static Object findDoubleCoords(
            final float px,
            final float py,
            final float[] positions,
            final int[] indices,
            final int step,
            final boolean skipFallback,
            final MethodHandle ctor)
            throws Throwable {
        final int triCount = indices.length / TRI_INDICES_STRIDE;
        final TriangleGrid grid = triCount >= MIN_TRIS_FOR_GRID ? resolveGrid(positions, indices, step) : null;
        if (triCount >= MIN_TRIS_FOR_GRID && grid == null) {
            return null;
        }
        final int hit = grid != null
                ? queryFirstTriangle(px, py, positions, indices, step, grid, false)
                : scanFirstTriangle(px, py, positions, indices, step, triCount, false);
        if (hit == Integer.MIN_VALUE) {
            return null;
        }
        final double[] weights = new double[TRI_INDICES_STRIDE];
        if (hit >= 0) {
            final int base = hit * TRI_INDICES_STRIDE;
            final int i0 = indices[base];
            final int i1 = indices[base + 1];
            final int i2 = indices[base + 2];
            final int p0 = i0 * step;
            final int p1 = i1 * step;
            final int p2 = i2 * step;
            if (!computeBarycentricDouble(
                    px,
                    py,
                    positions[p0],
                    positions[p0 + 1],
                    positions[p1],
                    positions[p1 + 1],
                    positions[p2],
                    positions[p2 + 1],
                    weights)) {
                return null;
            }
            return ctor.invoke(i0, weights[0], i1, weights[1], i2, weights[2]);
        }
        if (skipFallback) {
            return NULL_SENTINEL;
        }
        final int best = findNearestWeightTriangleDouble(px, py, positions, indices, step, triCount, weights);
        if (best == Integer.MIN_VALUE) {
            return null;
        }
        if (best < 0) {
            return NULL_SENTINEL;
        }
        final int base = best * TRI_INDICES_STRIDE;
        return ctor.invoke(
                indices[base], weights[0], indices[base + 1], weights[1], indices[base + 2], weights[2]);
    }

    private static Object findFloatCoords(
            final float px,
            final float py,
            final float[] positions,
            final int[] indices,
            final int step,
            final boolean skipFallback,
            final MethodHandle ctor)
            throws Throwable {
        final int triCount = indices.length / TRI_INDICES_STRIDE;
        final TriangleGrid grid = triCount >= MIN_TRIS_FOR_GRID ? resolveGrid(positions, indices, step) : null;
        if (triCount >= MIN_TRIS_FOR_GRID && grid == null) {
            return null;
        }
        final int hit = grid != null
                ? queryFirstTriangle(px, py, positions, indices, step, grid, false)
                : scanFirstTriangle(px, py, positions, indices, step, triCount, false);
        if (hit == Integer.MIN_VALUE) {
            return null;
        }
        final float[] weights = new float[TRI_INDICES_STRIDE];
        if (hit >= 0) {
            final int base = hit * TRI_INDICES_STRIDE;
            final int i0 = indices[base];
            final int i1 = indices[base + 1];
            final int i2 = indices[base + 2];
            final int p0 = i0 * step;
            final int p1 = i1 * step;
            final int p2 = i2 * step;
            if (!computeBarycentricFloat(
                    px,
                    py,
                    positions[p0],
                    positions[p0 + 1],
                    positions[p1],
                    positions[p1 + 1],
                    positions[p2],
                    positions[p2 + 1],
                    weights)) {
                return null;
            }
            return ctor.invoke(i0, weights[0], i1, weights[1], i2, weights[2]);
        }
        if (skipFallback) {
            return NULL_SENTINEL;
        }
        final int best = findNearestWeightTriangleFloat(px, py, positions, indices, step, triCount, weights);
        if (best == Integer.MIN_VALUE) {
            return null;
        }
        if (best < 0) {
            return NULL_SENTINEL;
        }
        final int base = best * TRI_INDICES_STRIDE;
        return ctor.invoke(
                indices[base], weights[0], indices[base + 1], weights[1], indices[base + 2], weights[2]);
    }

    private static int findNearestWeightTriangleDouble(
            final float px,
            final float py,
            final float[] positions,
            final int[] indices,
            final int step,
            final int triCount,
            final double[] bestWeights) {
        double minSum = Double.MAX_VALUE;
        int bestTri = -1;
        final double[] tmp = new double[TRI_INDICES_STRIDE];
        for (int t = 0; t < triCount; t++) {
            final int base = t * TRI_INDICES_STRIDE;
            final int i0 = indices[base];
            final int i1 = indices[base + 1];
            final int i2 = indices[base + 2];
            if (!validateVertexOffset(positions, i0, step)
                    || !validateVertexOffset(positions, i1, step)
                    || !validateVertexOffset(positions, i2, step)) {
                return Integer.MIN_VALUE;
            }
            final int p0 = i0 * step;
            final int p1 = i1 * step;
            final int p2 = i2 * step;
            if (computeBarycentricDouble(
                    px,
                    py,
                    positions[p0],
                    positions[p0 + 1],
                    positions[p1],
                    positions[p1 + 1],
                    positions[p2],
                    positions[p2 + 1],
                    tmp)) {
                final double sum = Math.abs(tmp[0]) + Math.abs(tmp[1]) + Math.abs(tmp[2]);
                if (sum < minSum) {
                    minSum = sum;
                    bestTri = t;
                    bestWeights[0] = tmp[0];
                    bestWeights[1] = tmp[1];
                    bestWeights[2] = tmp[2];
                }
            }
        }
        return bestTri;
    }

    private static int findNearestWeightTriangleFloat(
            final float px,
            final float py,
            final float[] positions,
            final int[] indices,
            final int step,
            final int triCount,
            final float[] bestWeights) {
        float minSum = Float.MAX_VALUE;
        int bestTri = -1;
        final float[] tmp = new float[TRI_INDICES_STRIDE];
        for (int t = 0; t < triCount; t++) {
            final int base = t * TRI_INDICES_STRIDE;
            final int i0 = indices[base];
            final int i1 = indices[base + 1];
            final int i2 = indices[base + 2];
            if (!validateVertexOffset(positions, i0, step)
                    || !validateVertexOffset(positions, i1, step)
                    || !validateVertexOffset(positions, i2, step)) {
                return Integer.MIN_VALUE;
            }
            final int p0 = i0 * step;
            final int p1 = i1 * step;
            final int p2 = i2 * step;
            if (computeBarycentricFloat(
                    px,
                    py,
                    positions[p0],
                    positions[p0 + 1],
                    positions[p1],
                    positions[p1 + 1],
                    positions[p2],
                    positions[p2 + 1],
                    tmp)) {
                final float sum = Math.abs(tmp[0]) + Math.abs(tmp[1]) + Math.abs(tmp[2]);
                if (sum < minSum) {
                    minSum = sum;
                    bestTri = t;
                    bestWeights[0] = tmp[0];
                    bestWeights[1] = tmp[1];
                    bestWeights[2] = tmp[2];
                }
            }
        }
        return bestTri;
    }

    private static int scanFirstTriangle(
            final float px,
            final float py,
            final float[] positions,
            final int[] indices,
            final int step,
            final int triCount,
            final boolean strict) {
        for (int t = 0; t < triCount; t++) {
            final int base = t * TRI_INDICES_STRIDE;
            final int i0 = indices[base];
            final int i1 = indices[base + 1];
            final int i2 = indices[base + 2];
            if (i0 == SENTINEL_INDEX && i1 == SENTINEL_INDEX && i2 == SENTINEL_INDEX) {
                continue;
            }
            if (!validateVertexOffset(positions, i0, step)
                    || !validateVertexOffset(positions, i1, step)
                    || !validateVertexOffset(positions, i2, step)) {
                return Integer.MIN_VALUE;
            }
            final int p0 = i0 * step;
            final int p1 = i1 * step;
            final int p2 = i2 * step;
            final boolean inside = strict
                    ? isInTriangleStrict(
                            px,
                            py,
                            positions[p0],
                            positions[p0 + 1],
                            positions[p1],
                            positions[p1 + 1],
                            positions[p2],
                            positions[p2 + 1])
                    : isInTriangleInclusive(
                            px,
                            py,
                            positions[p0],
                            positions[p0 + 1],
                            positions[p1],
                            positions[p1 + 1],
                            positions[p2],
                            positions[p2 + 1]);
            if (inside) {
                return t;
            }
        }
        return -1;
    }

    private static int queryFirstTriangle(
            final float px,
            final float py,
            final float[] positions,
            final int[] indices,
            final int step,
            final TriangleGrid grid,
            final boolean strict) {
        if (Float.isNaN(px) || Float.isNaN(py)) {
            return scanOverList(px, py, positions, indices, step, grid.overflowTris, Integer.MAX_VALUE, strict);
        }
        int firstHit = Integer.MAX_VALUE;
        if (px >= grid.minX && px <= grid.maxX && py >= grid.minY && py <= grid.maxY) {
            final int cx = clampCell((int) Math.floor((px - grid.minX) * grid.invCellW), grid.gridW);
            final int cy = clampCell((int) Math.floor((py - grid.minY) * grid.invCellH), grid.gridH);
            final int[] cellTris = grid.cells[cy * grid.gridW + cx];
            if (cellTris != null) {
                final int cellHit = scanOverList(px, py, positions, indices, step, cellTris, Integer.MAX_VALUE, strict);
                if (cellHit == Integer.MIN_VALUE) {
                    return Integer.MIN_VALUE;
                }
                if (cellHit >= 0) {
                    firstHit = cellHit;
                }
            }
        }
        if (grid.overflowTris.length > 0) {
            final int overHit =
                    scanOverList(px, py, positions, indices, step, grid.overflowTris, firstHit, strict);
            if (overHit == Integer.MIN_VALUE) {
                return Integer.MIN_VALUE;
            }
            if (overHit >= 0 && overHit < firstHit) {
                firstHit = overHit;
            }
        }
        return firstHit == Integer.MAX_VALUE ? -1 : firstHit;
    }

    private static int scanOverList(
            final float px,
            final float py,
            final float[] positions,
            final int[] indices,
            final int step,
            final int[] triIds,
            final int limitTri,
            final boolean strict) {
        for (final int t : triIds) {
            if (t >= limitTri) {
                break;
            }
            final int base = t * TRI_INDICES_STRIDE;
            final int i0 = indices[base];
            final int i1 = indices[base + 1];
            final int i2 = indices[base + 2];
            final int p0 = i0 * step;
            final int p1 = i1 * step;
            final int p2 = i2 * step;
            final float ax = positions[p0];
            final float ay = positions[p0 + 1];
            final float bx = positions[p1];
            final float by = positions[p1 + 1];
            final float cx = positions[p2];
            final float cy = positions[p2 + 1];
            final boolean inside = strict
                    ? isInTriangleStrict(px, py, ax, ay, bx, by, cx, cy)
                    : isInTriangleInclusive(px, py, ax, ay, bx, by, cx, cy);
            if (inside) {
                return t;
            }
        }
        return -1;
    }

    static boolean isInTriangleInclusive(
            final float px,
            final float py,
            final float ax,
            final float ay,
            final float bx,
            final float by,
            final float cx,
            final float cy) {
        final float c0 = (bx - ax) * (py - by) - (by - ay) * (px - bx);
        final float c1 = (cx - bx) * (py - cy) - (cy - by) * (px - cx);
        final float c2 = (ax - cx) * (py - ay) - (ay - cy) * (px - ax);
        return (c0 >= 0.0f && c1 >= 0.0f && c2 >= 0.0f) || (c0 <= 0.0f && c1 <= 0.0f && c2 <= 0.0f);
    }

    static boolean isInTriangleStrict(
            final float px,
            final float py,
            final float ax,
            final float ay,
            final float bx,
            final float by,
            final float cx,
            final float cy) {
        final float c0 = (px - ax) * (by - ay) - (py - ay) * (bx - ax);
        final float c1 = (px - bx) * (cy - by) - (py - by) * (cx - bx);
        if (!((c0 > 0.0f && c1 > 0.0f) || (c0 < 0.0f && c1 < 0.0f))) {
            return false;
        }
        final float c2 = (px - cx) * (ay - cy) - (py - cy) * (ax - cx);
        return (c0 > 0.0f && c2 > 0.0f) || (c0 < 0.0f && c2 < 0.0f);
    }

    static boolean computeBarycentricDouble(
            final float px,
            final float py,
            final float ax,
            final float ay,
            final float bx,
            final float by,
            final float cx,
            final float cy,
            final double[] out) {
        final double bax = (double) bx - (double) ax;
        final double bay = (double) by - (double) ay;
        final double cax = (double) cx - (double) ax;
        final double cay = (double) cy - (double) ay;
        final double det = cax * bay - cay * bax;
        if (det == 0.0) {
            return false;
        }
        final double dx = (double) px - (double) ax;
        final double dy = (double) py - (double) ay;
        final double w2 = (dx * bay - dy * bax) / det;
        final double w1 = bax != 0.0 ? (dx - w2 * cax) / bax : (dy - w2 * cay) / bay;
        out[0] = 1.0 - w1 - w2;
        out[1] = w1;
        out[2] = w2;
        return true;
    }

    static boolean computeBarycentricFloat(
            final float px,
            final float py,
            final float ax,
            final float ay,
            final float bx,
            final float by,
            final float cx,
            final float cy,
            final float[] out) {
        final float bax = bx - ax;
        final float bay = by - ay;
        final float cax = cx - ax;
        final float cay = cy - ay;
        final float det = cax * bay - cay * bax;
        if (det == 0.0f) {
            return false;
        }
        final float dx = px - ax;
        final float dy = py - ay;
        final float w2 = (dx * bay - dy * bax) / det;
        final float w1 = bax != 0.0f ? (dx - w2 * cax) / bax : (dy - w2 * cay) / bay;
        out[0] = 1.0f - w1 - w2;
        out[1] = w1;
        out[2] = w2;
        return true;
    }

    private static TriangleGrid resolveGrid(final float[] positions, final int[] indices, final int step) {
        final long digest = MeshArrayFingerprint.fingerprint(positions, indices, step);
        final GridCacheEntry[] ring = GRID_CACHE.get();
        for (int i = 0; i < ring.length; i++) {
            final GridCacheEntry entry = ring[i];
            if (entry != null
                    && entry.step == step
                    && entry.digest == digest
                    && entry.posRef.get() == positions
                    && entry.idxRef.get() == indices) {
                if (i > 0) {
                    System.arraycopy(ring, 0, ring, 1, i);
                    ring[0] = entry;
                }
                return entry.grid;
            }
        }
        final TriangleGrid built = buildGrid(positions, indices, step);
        if (built == null) {
            return null;
        }
        System.arraycopy(ring, 0, ring, 1, ring.length - 1);
        ring[0] = new GridCacheEntry(positions, indices, step, digest, built);
        return built;
    }

    private static TriangleGrid buildGrid(final float[] positions, final int[] indices, final int step) {
        final int triCount = indices.length / TRI_INDICES_STRIDE;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        int validCount = 0;
        for (int t = 0; t < triCount; t++) {
            final int base = t * TRI_INDICES_STRIDE;
            final int i0 = indices[base];
            final int i1 = indices[base + 1];
            final int i2 = indices[base + 2];
            if (i0 == SENTINEL_INDEX && i1 == SENTINEL_INDEX && i2 == SENTINEL_INDEX) {
                continue;
            }
            if (!validateVertexOffset(positions, i0, step)
                    || !validateVertexOffset(positions, i1, step)
                    || !validateVertexOffset(positions, i2, step)) {
                return null;
            }
            final int p0 = i0 * step;
            final int p1 = i1 * step;
            final int p2 = i2 * step;
            final float ax = positions[p0];
            final float ay = positions[p0 + 1];
            final float bx = positions[p1];
            final float by = positions[p1 + 1];
            final float cx = positions[p2];
            final float cy = positions[p2 + 1];
            if (!Float.isFinite(ax)
                    || !Float.isFinite(ay)
                    || !Float.isFinite(bx)
                    || !Float.isFinite(by)
                    || !Float.isFinite(cx)
                    || !Float.isFinite(cy)) {
                continue;
            }
            validCount++;
            final float tMinX = Math.min(ax, Math.min(bx, cx));
            final float tMaxX = Math.max(ax, Math.max(bx, cx));
            final float tMinY = Math.min(ay, Math.min(by, cy));
            final float tMaxY = Math.max(ay, Math.max(by, cy));
            if (tMinX < minX) minX = tMinX;
            if (tMaxX > maxX) maxX = tMaxX;
            if (tMinY < minY) minY = tMinY;
            if (tMaxY > maxY) maxY = tMaxY;
        }
        if (validCount == 0) {
            return new TriangleGrid(0.0, 0.0, 0.0, 0.0, 1, 1, 1.0, 1.0, new int[1][], collectAllIds(indices, step, positions));
        }

        final double spanX = Math.max(maxX - minX, MIN_CELL_SPAN);
        final double spanY = Math.max(maxY - minY, MIN_CELL_SPAN);
        final double padX = Math.max(AABB_MIN_MARGIN, spanX * AABB_MARGIN_RATIO);
        final double padY = Math.max(AABB_MIN_MARGIN, spanY * AABB_MARGIN_RATIO);
        final double gMinX = minX - padX;
        final double gMaxX = maxX + padX;
        final double gMinY = minY - padY;
        final double gMaxY = maxY + padY;

        final int[] dims = MeshArrayFingerprint.gridDimensions(gMaxX - gMinX, gMaxY - gMinY, validCount);
        final int gridW = dims[0];
        final int gridH = dims[1];
        final double invCellW = gridW / (gMaxX - gMinX);
        final double invCellH = gridH / (gMaxY - gMinY);
        final int totalCells = gridW * gridH;

        final int[] counts = new int[totalCells];
        int overflowCount = 0;

        for (int t = 0; t < triCount; t++) {
            final int base = t * TRI_INDICES_STRIDE;
            final int i0 = indices[base];
            final int i1 = indices[base + 1];
            final int i2 = indices[base + 2];
            if (i0 == SENTINEL_INDEX && i1 == SENTINEL_INDEX && i2 == SENTINEL_INDEX) {
                continue;
            }
            final int p0 = i0 * step;
            final int p1 = i1 * step;
            final int p2 = i2 * step;
            final float ax = positions[p0];
            final float ay = positions[p0 + 1];
            final float bx = positions[p1];
            final float by = positions[p1 + 1];
            final float cx = positions[p2];
            final float cy = positions[p2 + 1];
            if (!Float.isFinite(ax)
                    || !Float.isFinite(ay)
                    || !Float.isFinite(bx)
                    || !Float.isFinite(by)
                    || !Float.isFinite(cx)
                    || !Float.isFinite(cy)) {
                overflowCount++;
                continue;
            }
            final int minCx = clampCell((int) Math.floor((Math.min(ax, Math.min(bx, cx)) - padX - gMinX) * invCellW), gridW);
            final int maxCx = clampCell((int) Math.floor((Math.max(ax, Math.max(bx, cx)) + padX - gMinX) * invCellW), gridW);
            final int minCy = clampCell((int) Math.floor((Math.min(ay, Math.min(by, cy)) - padY - gMinY) * invCellH), gridH);
            final int maxCy = clampCell((int) Math.floor((Math.max(ay, Math.max(by, cy)) + padY - gMinY) * invCellH), gridH);
            final int cellSpan = (maxCx - minCx + 1) * (maxCy - minCy + 1);
            if (cellSpan > MAX_CELLS_PER_TRIANGLE) {
                overflowCount++;
                continue;
            }
            for (int cyIdx = minCy; cyIdx <= maxCy; cyIdx++) {
                final int row = cyIdx * gridW;
                for (int cxIdx = minCx; cxIdx <= maxCx; cxIdx++) {
                    counts[row + cxIdx]++;
                }
            }
        }

        final int[][] cells = new int[totalCells][];
        final int[] writePos = new int[totalCells];
        for (int c = 0; c < totalCells; c++) {
            if (counts[c] > 0) {
                cells[c] = new int[counts[c]];
            }
        }
        final int[] overflow = new int[overflowCount];
        int overflowPos = 0;

        for (int t = 0; t < triCount; t++) {
            final int base = t * TRI_INDICES_STRIDE;
            final int i0 = indices[base];
            final int i1 = indices[base + 1];
            final int i2 = indices[base + 2];
            if (i0 == SENTINEL_INDEX && i1 == SENTINEL_INDEX && i2 == SENTINEL_INDEX) {
                continue;
            }
            final int p0 = i0 * step;
            final int p1 = i1 * step;
            final int p2 = i2 * step;
            final float ax = positions[p0];
            final float ay = positions[p0 + 1];
            final float bx = positions[p1];
            final float by = positions[p1 + 1];
            final float cx = positions[p2];
            final float cy = positions[p2 + 1];
            if (!Float.isFinite(ax)
                    || !Float.isFinite(ay)
                    || !Float.isFinite(bx)
                    || !Float.isFinite(by)
                    || !Float.isFinite(cx)
                    || !Float.isFinite(cy)) {
                overflow[overflowPos++] = t;
                continue;
            }
            final int minCx = clampCell((int) Math.floor((Math.min(ax, Math.min(bx, cx)) - padX - gMinX) * invCellW), gridW);
            final int maxCx = clampCell((int) Math.floor((Math.max(ax, Math.max(bx, cx)) + padX - gMinX) * invCellW), gridW);
            final int minCy = clampCell((int) Math.floor((Math.min(ay, Math.min(by, cy)) - padY - gMinY) * invCellH), gridH);
            final int maxCy = clampCell((int) Math.floor((Math.max(ay, Math.max(by, cy)) + padY - gMinY) * invCellH), gridH);
            final int cellSpan = (maxCx - minCx + 1) * (maxCy - minCy + 1);
            if (cellSpan > MAX_CELLS_PER_TRIANGLE) {
                overflow[overflowPos++] = t;
                continue;
            }
            for (int cyIdx = minCy; cyIdx <= maxCy; cyIdx++) {
                final int row = cyIdx * gridW;
                for (int cxIdx = minCx; cxIdx <= maxCx; cxIdx++) {
                    final int cellId = row + cxIdx;
                    cells[cellId][writePos[cellId]++] = t;
                }
            }
        }
        return new TriangleGrid(gMinX, gMaxX, gMinY, gMaxY, gridW, gridH, invCellW, invCellH, cells, overflow);
    }

    private static int[] collectAllIds(final int[] indices, final int step, final float[] positions) {
        final int triCount = indices.length / TRI_INDICES_STRIDE;
        int valid = 0;
        for (int t = 0; t < triCount; t++) {
            final int base = t * TRI_INDICES_STRIDE;
            final int i0 = indices[base];
            final int i1 = indices[base + 1];
            final int i2 = indices[base + 2];
            if (!(i0 == SENTINEL_INDEX && i1 == SENTINEL_INDEX && i2 == SENTINEL_INDEX)
                    && validateVertexOffset(positions, i0, step)
                    && validateVertexOffset(positions, i1, step)
                    && validateVertexOffset(positions, i2, step)) {
                valid++;
            }
        }
        final int[] ids = new int[valid];
        int pos = 0;
        for (int t = 0; t < triCount; t++) {
            final int base = t * TRI_INDICES_STRIDE;
            final int i0 = indices[base];
            final int i1 = indices[base + 1];
            final int i2 = indices[base + 2];
            if (!(i0 == SENTINEL_INDEX && i1 == SENTINEL_INDEX && i2 == SENTINEL_INDEX)) {
                ids[pos++] = t;
            }
        }
        return ids;
    }

    private static boolean validateVertexOffset(final float[] positions, final int index, final int step) {
        if (index < 0) {
            return false;
        }
        final long offset = (long) index * step;
        return offset + 1L < positions.length;
    }

    private static int clampCell(final int cell, final int limit) {
        if (cell < 0) {
            return 0;
        }
        return cell >= limit ? limit - 1 : cell;
    }

    private static MethodHandle resolveResultCtor(
            final ClassLoader loader, final String className, final MethodType signature) {
        if (loader == null) {
            return null;
        }
        try {
            final Class<?> target = Class.forName(className, false, loader);
            return MethodHandles.publicLookup().findConstructor(target, signature);
        } catch (final ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static final class VectorAccess {

        final MethodHandle getX;
        final MethodHandle getY;

        private VectorAccess(final MethodHandle getX, final MethodHandle getY) {
            this.getX = getX;
            this.getY = getY;
        }

        static VectorAccess resolve(final Class<?> type) {
            try {
                final MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                return new VectorAccess(
                        lookup.findVirtual(type, "getX", MethodType.methodType(float.class)),
                        lookup.findVirtual(type, "getY", MethodType.methodType(float.class)));
            } catch (final ReflectiveOperationException failure) {
                throw new IllegalStateException("Failed to bind vector coordinate accessors for " + type, failure);
            }
        }
    }

    private record GridCacheEntry(
            WeakReference<float[]> posRef,
            WeakReference<int[]> idxRef,
            int step,
            long digest,
            TriangleGrid grid) {

        GridCacheEntry(
                final float[] positions,
                final int[] indices,
                final int step,
                final long digest,
                final TriangleGrid grid) {
            this(new WeakReference<>(positions), new WeakReference<>(indices), step, digest, grid);
        }
    }

    private record TriangleGrid(
            double minX,
            double maxX,
            double minY,
            double maxY,
            int gridW,
            int gridH,
            double invCellW,
            double invCellH,
            int[][] cells,
            int[] overflowTris) {}
}
