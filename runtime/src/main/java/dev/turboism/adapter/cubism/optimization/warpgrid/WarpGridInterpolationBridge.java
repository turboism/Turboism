package dev.turboism.adapter.cubism.optimization.warpgrid;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.reflect.HostHandles.HostField;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Publishes runtime gates and out-of-bounds run handling for warp grid interpolation.
 *
 * <p>The woven host method executes the in-bounds fast path without allocations and invokes the
 * bound {@link MethodHandle} only for contiguous out-of-bounds runs, returning the next in-bounds
 * index so remaining vertices continue inside the compiled loop without fallback overhead.</p>
 */
public final class WarpGridInterpolationBridge implements AutoCloseable {

    /** Startup toggle property for warp grid interpolation fast path. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.warpGridInterpolation";

    /** System property slot holding the {@link AtomicBoolean} diagnostic gate. */
    public static final String ACTIVE_PROPERTY = "turboism.warp-grid-interpolation.active";

    /** System property slot holding the {@link MethodHandle} out-of-bounds run handler. */
    public static final String OOB_PROPERTY = "turboism.warp-grid-interpolation.oob";

    /** System property slot holding the {@link Supplier} counters snapshot. */
    public static final String STATS_PROPERTY = "turboism.warp-grid-interpolation.stats";

    private static final float ZERO_F = 0.0f;
    private static final float ONE_F = 1.0f;
    private static final float TWO_F = 2.0f;
    private static final float THREE_F = 3.0f;
    private static final float NEG_TWO_F = -2.0f;
    private static final float HALF_F = 0.5f;
    private static final float QUARTER_F = 0.25f;

    private static final int ORIGIN_INDEX = 0;
    private static final int CELL_STEP = 1;
    private static final int SHIFT_PAIR = 1;
    private static final int Y_OFFSET = 1;
    private static final int NEXT_X_OFFSET = 2;
    private static final int NEXT_Y_OFFSET = 3;

    private static final int BASIS_CX = 0;
    private static final int BASIS_CY = 1;
    private static final int BASIS_UX = 2;
    private static final int BASIS_UY = 3;
    private static final int BASIS_VX = 4;
    private static final int BASIS_VY = 5;
    private static final int BASIS_LENGTH = 6;

    private static final long ZERO_COUNT = 0L;
    private static final long BOUND_PRESENT = 1L;
    private static final long BOUND_ABSENT = 0L;

    private static final String OOB_HANDLER_METHOD = "handleOobRun";
    private static final MethodType OOB_HANDLER_TYPE = MethodType.methodType(
            int.class,
            float[].class,
            float[].class,
            int.class,
            int.class,
            int.class,
            float[].class,
            int.class,
            int.class,
            boolean.class);

    private static final String ERROR_CALC_FORMAT = "error calc : %.4f , %.4f\t\t\t\t\t@@BDBoxGrid\n";
    private static final String STAT_OOB_TAIL_CALLS = "oobTailCalls";
    private static final String STAT_OOB_POINTS = "oobPoints";
    private static final String STAT_IN_BOUNDS_FALLBACK_POINTS = "inBoundsFallbackPoints";
    private static final String STAT_FAILURES = "failures";
    private static final String STAT_BOUND = "bound";
    private static final String FAILED_BIND_HOST_ERROR = "failed to bind warp grid interpolation host handles";
    private static final String FAILED_BIND_OOB_ERROR = "failed to bind warp grid OOB method handle";
    private static final String OOB_HANDLER_FAILED_ERROR = "warp grid out-of-bounds handler failed";

    private final Class<?> utilsClass;
    private final HostField singletonField;
    private final MethodHandle transformHandle;
    private final MethodHandle oobHandle;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final Set<ThreadStats> threadStatsSet = ConcurrentHashMap.newKeySet();
    private final AtomicLong retiredOobTailCalls = new AtomicLong();
    private final AtomicLong retiredOobPoints = new AtomicLong();
    private final AtomicLong retiredInBoundsFallbackPoints = new AtomicLong();
    private final AtomicLong retiredFailures = new AtomicLong();
    private final ThreadLocal<ThreadStats> threadStats = ThreadLocal.withInitial(this::registerThreadStats);
    private final Supplier<Map<String, Long>> statsSupplier = this::snapshot;

    private WarpGridInterpolationBridge(
            final Class<?> utilsClass, final HostField singletonField, final MethodHandle transformHandle) {
        this.utilsClass = Objects.requireNonNull(utilsClass, "utilsClass");
        this.singletonField = Objects.requireNonNull(singletonField, "singletonField");
        this.transformHandle = Objects.requireNonNull(transformHandle, "transformHandle");
        try {
            this.oobHandle = MethodHandles.lookup()
                    .findVirtual(WarpGridInterpolationBridge.class, OOB_HANDLER_METHOD, OOB_HANDLER_TYPE)
                    .bindTo(this);
        } catch (final ReflectiveOperationException e) {
            throw new IllegalStateException(FAILED_BIND_OOB_ERROR, e);
        }
    }

    /**
     * Resolves reviewed host warp-grid members and binds the bridge.
     *
     * @param hostLoader admitted host classloader
     * @return verified bridge ready to publish system property slots
     */
    public static WarpGridInterpolationBridge open(final ClassLoader hostLoader) {
        try {
            final Class<?> utilsClass = HostHandles.load(hostLoader, WarpGridInterpolationTarget.OWNER);
            final HostField singletonField = HostHandles.field(utilsClass, WarpGridInterpolationTarget.SINGLETON_FIELD);
            final MethodHandle transformHandle = HostHandles.virtual(
                    utilsClass,
                    WarpGridInterpolationTarget.METHOD,
                    WarpGridInterpolationTarget.DESCRIPTOR,
                    MethodType.methodType(
                            void.class,
                            Object.class,
                            float[].class,
                            float[].class,
                            int.class,
                            int.class,
                            int.class,
                            float[].class,
                            int.class,
                            int.class,
                            boolean.class));
            verifyDeformerContracts(hostLoader);
            return new WarpGridInterpolationBridge(utilsClass, singletonField, transformHandle);
        } catch (final Throwable t) {
            FatalErrors.rethrowIfFatal(t);
            throw new IllegalStateException(FAILED_BIND_HOST_ERROR, t);
        }
    }

    private static void verifyDeformerContracts(final ClassLoader hostLoader) throws ReflectiveOperationException {
        final Class<?> deformerClass = HostHandles.load(hostLoader, WarpGridInterpolationTarget.WARP_DEFORMER_OWNER);
        HostHandles.virtual(
                deformerClass,
                WarpGridInterpolationTarget.GET_CALCULATED_FORM_METHOD,
                WarpGridInterpolationTarget.GET_CALCULATED_FORM_DESCRIPTOR,
                MethodType.methodType(Object.class, Object.class));
        HostHandles.virtual(
                deformerClass,
                WarpGridInterpolationTarget.GET_SOURCE_METHOD,
                WarpGridInterpolationTarget.GET_SOURCE_DESCRIPTOR,
                MethodType.methodType(Object.class, Object.class));

        final Class<?> formClass = HostHandles.load(hostLoader, WarpGridInterpolationTarget.WARP_FORM_OWNER);
        HostHandles.virtual(
                formClass,
                WarpGridInterpolationTarget.GET_POSITIONS_METHOD,
                WarpGridInterpolationTarget.GET_POSITIONS_DESCRIPTOR,
                MethodType.methodType(float[].class, Object.class));

        final Class<?> sourceClass = HostHandles.load(hostLoader, WarpGridInterpolationTarget.WARP_SOURCE_OWNER);
        HostHandles.virtual(
                sourceClass,
                WarpGridInterpolationTarget.GET_COL_METHOD,
                WarpGridInterpolationTarget.GET_COL_DESCRIPTOR,
                MethodType.methodType(int.class, Object.class));
        HostHandles.virtual(
                sourceClass,
                WarpGridInterpolationTarget.GET_ROW_METHOD,
                WarpGridInterpolationTarget.GET_ROW_DESCRIPTOR,
                MethodType.methodType(int.class, Object.class));
        HostHandles.virtual(
                sourceClass,
                WarpGridInterpolationTarget.IS_QUAD_TRANSFORM_METHOD,
                WarpGridInterpolationTarget.IS_QUAD_TRANSFORM_DESCRIPTOR,
                MethodType.methodType(boolean.class, Object.class));
    }

    /** Returns the resolved host helper class bound by this bridge. */
    public Class<?> utilsClass() {
        return utilsClass;
    }

    /** Returns the bound host method handle for verification tests. */
    public MethodHandle transformHandle() {
        return transformHandle;
    }

    /** Publishes the active gate, out-of-bounds handler, and diagnostic counters. */
    public void install() {
        active.set(true);
        final Properties props = System.getProperties();
        props.put(ACTIVE_PROPERTY, active);
        props.put(OOB_PROPERTY, oobHandle);
        props.put(STATS_PROPERTY, statsSupplier);
    }

    /** Disables the fast-path gate and removes published slots when unchanged. */
    @Override
    public void close() {
        active.set(false);
        threadStats.remove();
        final Properties props = System.getProperties();
        props.remove(ACTIVE_PROPERTY, active);
        props.remove(OOB_PROPERTY, oobHandle);
        props.remove(STATS_PROPERTY, statsSupplier);
    }

    /** Returns an immutable counters snapshot for diagnostics. */
    public Map<String, Long> snapshot() {
        long calls = retiredOobTailCalls.get();
        long points = retiredOobPoints.get();
        long fallbackPoints = retiredInBoundsFallbackPoints.get();
        long failCount = retiredFailures.get();
        final Iterator<ThreadStats> iterator = threadStatsSet.iterator();
        while (iterator.hasNext()) {
            final ThreadStats stats = iterator.next();
            calls += stats.oobTailCalls;
            points += stats.oobPoints;
            fallbackPoints += stats.inBoundsFallbackPoints;
            failCount += stats.failures;
            if (!stats.owner.isAlive()) {
                retiredOobTailCalls.addAndGet(stats.oobTailCalls);
                retiredOobPoints.addAndGet(stats.oobPoints);
                retiredInBoundsFallbackPoints.addAndGet(stats.inBoundsFallbackPoints);
                retiredFailures.addAndGet(stats.failures);
                iterator.remove();
            }
        }
        return Map.of(
                STAT_OOB_TAIL_CALLS, calls,
                STAT_OOB_POINTS, points,
                STAT_IN_BOUNDS_FALLBACK_POINTS, fallbackPoints,
                STAT_FAILURES, failCount,
                STAT_BOUND, isSingletonBound() ? BOUND_PRESENT : BOUND_ABSENT);
    }

    private ThreadStats registerThreadStats() {
        final ThreadStats stats = new ThreadStats();
        threadStatsSet.add(stats);
        return stats;
    }

    private boolean isSingletonBound() {
        try {
            return singletonField.get(null) != null;
        } catch (final Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return false;
        }
    }

    @SuppressWarnings("unused")
    private int handleOobRun(
            final float[] src,
            final float[] dst,
            final int startIndex,
            final int limit,
            final int srcStep,
            final float[] pt,
            final int col,
            final int row,
            final boolean isQuad) {
        final ThreadStats stats = threadStats.get();
        try {
            return processOobRun(src, dst, startIndex, limit, srcStep, pt, col, row, stats);
        } catch (final Throwable t) {
            FatalErrors.rethrowIfFatal(t);
            stats.failures++;
            if (t instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (t instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(OOB_HANDLER_FAILED_ERROR, t);
        }
    }

    private static int processOobRun(
            final float[] src,
            final float[] dst,
            final int startIndex,
            final int limit,
            final int srcStep,
            final float[] pt,
            final int col,
            final int row,
            final ThreadStats stats) {
        final int colPlus1 = col + CELL_STEP;
        final float fCol = (float) col;
        final float fRow = (float) row;
        final float[] basis = stats.basis;
        computeCornerBasis(pt, col, row, colPlus1, basis);

        long oobCount = ZERO_COUNT;
        int i = startIndex;
        while (i < limit) {
            final float sx = src[i];
            final float sy = src[i + Y_OFFSET];
            final float x = sx * fCol;
            final float y = sy * fRow;

            // Keep exact host NaN comparison routing so NaN and in-bounds points resume ASM.
            if (!(x < ZERO_F) && !(y < ZERO_F) && !(fCol <= x) && !(fRow <= y)) {
                break;
            }
            oobCount++;
            interpolateOobPoint(dst, i, pt, col, row, colPlus1, fCol, fRow, sx, sy, x, y, basis);
            i += srcStep;
        }

        stats.oobTailCalls++;
        stats.oobPoints += oobCount;
        return i;
    }

    /**
     * Completes transformation from an out-of-bounds index with strict IEEE-754 order.
     */
    void transformTail(
            final float[] src,
            final float[] dst,
            final int startIndex,
            final int limit,
            final int srcStep,
            final float[] pt,
            final int col,
            final int row,
            final boolean isQuad) {
        final ThreadStats stats = threadStats.get();
        if (isQuad) {
            transformTailQuad(src, dst, startIndex, limit, srcStep, pt, col, row, stats);
        } else {
            transformTailTriangle(src, dst, startIndex, limit, srcStep, pt, col, row, stats);
        }
    }

    private static void transformTailQuad(
            final float[] src,
            final float[] dst,
            final int startIndex,
            final int limit,
            final int srcStep,
            final float[] pt,
            final int col,
            final int row,
            final ThreadStats stats) {
        final int colPlus1 = col + CELL_STEP;
        final int rowStride = colPlus1 << SHIFT_PAIR;
        final float fCol = (float) col;
        final float fRow = (float) row;
        final float[] basis = stats.basis;

        boolean basisReady = false;
        long inBoundsCount = ZERO_COUNT;
        long oobCount = ZERO_COUNT;

        for (int i = startIndex; i < limit; i += srcStep) {
            final float sx = src[i];
            final float sy = src[i + Y_OFFSET];
            final float x = sx * fCol;
            final float y = sy * fRow;

            // Keep exact host NaN comparison routing so NaN falls into in-bounds math.
            if (!(x < ZERO_F) && !(y < ZERO_F) && !(fCol <= x) && !(fRow <= y)) {
                inBoundsCount++;
                final int xi = (int) x;
                final int yi = (int) y;
                final float xRate = x - (float) xi;
                final float yRate = y - (float) yi;
                final int index = (xi + yi * colPlus1) << SHIFT_PAIR;
                final int nextRow = index + rowStride;
                final float invX = ONE_F - xRate;
                final float invY = ONE_F - yRate;
                dst[i] = ((pt[index] * invX) * invY + (pt[index + NEXT_X_OFFSET] * xRate) * invY)
                        + (pt[nextRow] * invX) * yRate
                        + (pt[nextRow + NEXT_X_OFFSET] * xRate) * yRate;
                dst[i + Y_OFFSET] = ((pt[index + Y_OFFSET] * invX) * invY + (pt[index + NEXT_Y_OFFSET] * xRate) * invY)
                        + (pt[nextRow + Y_OFFSET] * invX) * yRate
                        + (pt[nextRow + NEXT_Y_OFFSET] * xRate) * yRate;
                continue;
            }

            oobCount++;
            if (!basisReady) {
                computeCornerBasis(pt, col, row, colPlus1, basis);
                basisReady = true;
            }
            interpolateOobPoint(dst, i, pt, col, row, colPlus1, fCol, fRow, sx, sy, x, y, basis);
        }

        flushTailCounters(stats, inBoundsCount, oobCount);
    }

    private static void transformTailTriangle(
            final float[] src,
            final float[] dst,
            final int startIndex,
            final int limit,
            final int srcStep,
            final float[] pt,
            final int col,
            final int row,
            final ThreadStats stats) {
        final int colPlus1 = col + CELL_STEP;
        final int rowStride = colPlus1 << SHIFT_PAIR;
        final float fCol = (float) col;
        final float fRow = (float) row;
        final float[] basis = stats.basis;

        boolean basisReady = false;
        long inBoundsCount = ZERO_COUNT;
        long oobCount = ZERO_COUNT;

        for (int i = startIndex; i < limit; i += srcStep) {
            final float sx = src[i];
            final float sy = src[i + Y_OFFSET];
            final float x = sx * fCol;
            final float y = sy * fRow;

            // Keep exact host NaN comparison routing so NaN falls into in-bounds math.
            if (!(x < ZERO_F) && !(y < ZERO_F) && !(fCol <= x) && !(fRow <= y)) {
                inBoundsCount++;
                final int xi = (int) x;
                final int yi = (int) y;
                final float xRate = x - (float) xi;
                final float yRate = y - (float) yi;
                final int index = (xi + yi * colPlus1) << SHIFT_PAIR;
                final int nextRow = index + rowStride;
                if (xRate + yRate < ONE_F) {
                    final float w00 = (ONE_F - xRate) - yRate;
                    dst[i] = (pt[index] * w00 + pt[index + NEXT_X_OFFSET] * xRate) + pt[nextRow] * yRate;
                    dst[i + Y_OFFSET] = (pt[index + Y_OFFSET] * w00 + pt[index + NEXT_Y_OFFSET] * xRate)
                            + pt[nextRow + Y_OFFSET] * yRate;
                } else {
                    final float w11 = (xRate - ONE_F) + yRate;
                    final float invX = ONE_F - xRate;
                    final float invY = ONE_F - yRate;
                    dst[i] =
                            (pt[nextRow + NEXT_X_OFFSET] * w11 + pt[nextRow] * invX) + pt[index + NEXT_X_OFFSET] * invY;
                    dst[i + Y_OFFSET] = (pt[nextRow + NEXT_Y_OFFSET] * w11 + pt[nextRow + Y_OFFSET] * invX)
                            + pt[index + NEXT_Y_OFFSET] * invY;
                }
                continue;
            }

            oobCount++;
            if (!basisReady) {
                computeCornerBasis(pt, col, row, colPlus1, basis);
                basisReady = true;
            }
            interpolateOobPoint(dst, i, pt, col, row, colPlus1, fCol, fRow, sx, sy, x, y, basis);
        }

        flushTailCounters(stats, inBoundsCount, oobCount);
    }

    private static void flushTailCounters(final ThreadStats stats, final long inBoundsCount, final long oobCount) {
        stats.oobTailCalls++;
        if (inBoundsCount != ZERO_COUNT) {
            stats.inBoundsFallbackPoints += inBoundsCount;
        }
        if (oobCount != ZERO_COUNT) {
            stats.oobPoints += oobCount;
        }
    }

    private static void interpolateOobPoint(
            final float[] dst,
            final int outIdx,
            final float[] pt,
            final int col,
            final int row,
            final int colPlus1,
            final float fCol,
            final float fRow,
            final float sx,
            final float sy,
            final float x,
            final float y,
            final float[] basis) {
        if (sx > NEG_TWO_F && sx < THREE_F && sy > NEG_TWO_F && sy < THREE_F) {
            interpolateBorderBand(dst, outIdx, pt, col, row, colPlus1, fCol, fRow, sx, sy, x, y, basis);
        } else {
            dst[outIdx] = (basis[BASIS_CX] + sx * basis[BASIS_UX]) + sy * basis[BASIS_VX];
            dst[outIdx + Y_OFFSET] = (basis[BASIS_CY] + sx * basis[BASIS_UY]) + sy * basis[BASIS_VY];
        }
    }

    private static void interpolateBorderBand(
            final float[] dst,
            final int outIdx,
            final float[] pt,
            final int col,
            final int row,
            final int colPlus1,
            final float fCol,
            final float fRow,
            final float sx,
            final float sy,
            final float x,
            final float y,
            final float[] basis) {
        if (sx <= ZERO_F) {
            interpolateLeftBand(dst, outIdx, pt, row, colPlus1, fRow, sx, sy, y, basis);
        } else if (sx >= ONE_F) {
            interpolateRightBand(dst, outIdx, pt, col, row, colPlus1, fRow, sx, sy, y, basis);
        } else if (sy <= ZERO_F) {
            interpolateBottomStrip(dst, outIdx, pt, col, fCol, sx, sy, x, basis);
        } else if (sy >= ONE_F) {
            interpolateTopStrip(dst, outIdx, pt, col, row, colPlus1, fCol, sx, sy, x, basis);
        } else {
            System.err.printf(ERROR_CALC_FORMAT, sx, sy);
        }
    }

    private static void interpolateLeftBand(
            final float[] dst,
            final int outIdx,
            final float[] pt,
            final int row,
            final int colPlus1,
            final float fRow,
            final float sx,
            final float sy,
            final float y,
            final float[] basis) {
        final float cx = basis[BASIS_CX];
        final float cy = basis[BASIS_CY];
        final float ux = basis[BASIS_UX];
        final float uy = basis[BASIS_UY];
        final float vx = basis[BASIS_VX];
        final float vy = basis[BASIS_VY];
        if (sy <= ZERO_F) {
            final float p11x = pt[ORIGIN_INDEX];
            final float p11y = pt[Y_OFFSET];
            final float p01x = cx - TWO_F * ux;
            final float p01y = cy - TWO_F * uy;
            final float p10x = cx - TWO_F * vx;
            final float p10y = cy - TWO_F * vy;
            final float p00x = p01x - TWO_F * vx;
            final float p00y = p01y - TWO_F * vy;
            final float tx = HALF_F * (sx - NEG_TWO_F);
            final float ty = HALF_F * (sy - NEG_TWO_F);
            interpolateTriangleCell(dst, outIdx, tx, ty, p00x, p00y, p10x, p10y, p01x, p01y, p11x, p11y);
        } else if (sy >= ONE_F) {
            final int rowBase = (row * colPlus1) << SHIFT_PAIR;
            final float p10x = pt[rowBase];
            final float p10y = pt[rowBase + Y_OFFSET];
            final float p00x = (cx - TWO_F * ux) + ONE_F * vx;
            final float p00y = (cy - TWO_F * uy) + ONE_F * vy;
            final float p11x = cx + THREE_F * vx;
            final float p11y = cy + THREE_F * vy;
            final float p01x = (cx - TWO_F * ux) + THREE_F * vx;
            final float p01y = (cy - TWO_F * uy) + THREE_F * vy;
            final float tx = HALF_F * (sx - NEG_TWO_F);
            final float ty = HALF_F * (sy - ONE_F);
            interpolateTriangleCell(dst, outIdx, tx, ty, p00x, p00y, p10x, p10y, p01x, p01y, p11x, p11y);
        } else {
            int iy = (int) y;
            if (iy == row) {
                iy = row - CELL_STEP;
            }
            final float tx = HALF_F * (sx - NEG_TWO_F);
            final float ty = y - (float) iy;
            final float t0 = (float) iy / fRow;
            final float t1 = (float) (iy + CELL_STEP) / fRow;
            final int idx0 = (iy * colPlus1) << SHIFT_PAIR;
            final int idx1 = ((iy + CELL_STEP) * colPlus1) << SHIFT_PAIR;
            final float p10x = pt[idx0];
            final float p10y = pt[idx0 + Y_OFFSET];
            final float p11x = pt[idx1];
            final float p11y = pt[idx1 + Y_OFFSET];
            final float p00x = (cx - TWO_F * ux) + t0 * vx;
            final float p00y = (cy - TWO_F * uy) + t0 * vy;
            final float p01x = (cx - TWO_F * ux) + t1 * vx;
            final float p01y = (cy - TWO_F * uy) + t1 * vy;
            interpolateTriangleCell(dst, outIdx, tx, ty, p00x, p00y, p10x, p10y, p01x, p01y, p11x, p11y);
        }
    }

    private static void interpolateRightBand(
            final float[] dst,
            final int outIdx,
            final float[] pt,
            final int col,
            final int row,
            final int colPlus1,
            final float fRow,
            final float sx,
            final float sy,
            final float y,
            final float[] basis) {
        final float cx = basis[BASIS_CX];
        final float cy = basis[BASIS_CY];
        final float ux = basis[BASIS_UX];
        final float uy = basis[BASIS_UY];
        final float vx = basis[BASIS_VX];
        final float vy = basis[BASIS_VY];
        if (sy <= ZERO_F) {
            final int cIdx = col << SHIFT_PAIR;
            final float p01x = pt[cIdx];
            final float p01y = pt[cIdx + Y_OFFSET];
            final float p11x = cx + THREE_F * ux;
            final float p11y = cy + THREE_F * uy;
            final float p00x = (cx + ONE_F * ux) - TWO_F * vx;
            final float p00y = (cy + ONE_F * uy) - TWO_F * vy;
            final float p10x = p11x - TWO_F * vx;
            final float p10y = p11y - TWO_F * vy;
            final float tx = HALF_F * (sx - ONE_F);
            final float ty = HALF_F * (sy - NEG_TWO_F);
            interpolateTriangleCell(dst, outIdx, tx, ty, p00x, p00y, p10x, p10y, p01x, p01y, p11x, p11y);
        } else if (sy >= ONE_F) {
            final int crIdx = (col + row * colPlus1) << SHIFT_PAIR;
            final float p00x = pt[crIdx];
            final float p00y = pt[crIdx + Y_OFFSET];
            final float p10x = (cx + THREE_F * ux) + ONE_F * vx;
            final float p10y = (cy + THREE_F * uy) + ONE_F * vy;
            final float p01x = (cx + ONE_F * ux) + THREE_F * vx;
            final float p01y = (cy + ONE_F * uy) + THREE_F * vy;
            final float p11x = (cx + THREE_F * ux) + THREE_F * vx;
            final float p11y = (cy + THREE_F * uy) + THREE_F * vy;
            final float tx = HALF_F * (sx - ONE_F);
            final float ty = HALF_F * (sy - ONE_F);
            interpolateTriangleCell(dst, outIdx, tx, ty, p00x, p00y, p10x, p10y, p01x, p01y, p11x, p11y);
        } else {
            int iy = (int) y;
            if (iy == row) {
                iy = row - CELL_STEP;
            }
            final float tx = HALF_F * (sx - ONE_F);
            final float ty = y - (float) iy;
            final float t0 = (float) iy / fRow;
            final float t1 = (float) (iy + CELL_STEP) / fRow;
            final int idx0 = (col + iy * colPlus1) << SHIFT_PAIR;
            final int idx1 = (col + (iy + CELL_STEP) * colPlus1) << SHIFT_PAIR;
            final float p00x = pt[idx0];
            final float p00y = pt[idx0 + Y_OFFSET];
            final float p01x = pt[idx1];
            final float p01y = pt[idx1 + Y_OFFSET];
            final float p10x = (cx + THREE_F * ux) + t0 * vx;
            final float p10y = (cy + THREE_F * uy) + t0 * vy;
            final float p11x = (cx + THREE_F * ux) + t1 * vx;
            final float p11y = (cy + THREE_F * uy) + t1 * vy;
            interpolateTriangleCell(dst, outIdx, tx, ty, p00x, p00y, p10x, p10y, p01x, p01y, p11x, p11y);
        }
    }

    private static void interpolateBottomStrip(
            final float[] dst,
            final int outIdx,
            final float[] pt,
            final int col,
            final float fCol,
            final float sx,
            final float sy,
            final float x,
            final float[] basis) {
        final float cx = basis[BASIS_CX];
        final float cy = basis[BASIS_CY];
        final float ux = basis[BASIS_UX];
        final float uy = basis[BASIS_UY];
        final float vx = basis[BASIS_VX];
        final float vy = basis[BASIS_VY];
        int ix = (int) x;
        if (ix == col) {
            ix = col - CELL_STEP;
        }
        final float tx = x - (float) ix;
        final float ty = HALF_F * (sy - NEG_TWO_F);
        final float s0 = (float) ix / fCol;
        final float s1 = (float) (ix + CELL_STEP) / fCol;
        final int idx0 = ix << SHIFT_PAIR;
        final int idx1 = (ix + CELL_STEP) << SHIFT_PAIR;
        final float p01x = pt[idx0];
        final float p01y = pt[idx0 + Y_OFFSET];
        final float p11x = pt[idx1];
        final float p11y = pt[idx1 + Y_OFFSET];
        final float p00x = (cx + s0 * ux) - TWO_F * vx;
        final float p00y = (cy + s0 * uy) - TWO_F * vy;
        final float p10x = (cx + s1 * ux) - TWO_F * vx;
        final float p10y = (cy + s1 * uy) - TWO_F * vy;
        interpolateTriangleCell(dst, outIdx, tx, ty, p00x, p00y, p10x, p10y, p01x, p01y, p11x, p11y);
    }

    private static void interpolateTopStrip(
            final float[] dst,
            final int outIdx,
            final float[] pt,
            final int col,
            final int row,
            final int colPlus1,
            final float fCol,
            final float sx,
            final float sy,
            final float x,
            final float[] basis) {
        final float cx = basis[BASIS_CX];
        final float cy = basis[BASIS_CY];
        final float ux = basis[BASIS_UX];
        final float uy = basis[BASIS_UY];
        final float vx = basis[BASIS_VX];
        final float vy = basis[BASIS_VY];
        int ix = (int) x;
        if (ix == col) {
            ix = col - CELL_STEP;
        }
        final float tx = x - (float) ix;
        final float ty = HALF_F * (sy - ONE_F);
        final float s0 = (float) ix / fCol;
        final float s1 = (float) (ix + CELL_STEP) / fCol;
        final int rowBase = row * colPlus1;
        final int idx0 = (ix + rowBase) << SHIFT_PAIR;
        final int idx1 = (ix + CELL_STEP + rowBase) << SHIFT_PAIR;
        final float p00x = pt[idx0];
        final float p00y = pt[idx0 + Y_OFFSET];
        final float p10x = pt[idx1];
        final float p10y = pt[idx1 + Y_OFFSET];
        final float p01x = (cx + s0 * ux) + THREE_F * vx;
        final float p01y = (cy + s0 * uy) + THREE_F * vy;
        final float p11x = (cx + s1 * ux) + THREE_F * vx;
        final float p11y = (cy + s1 * uy) + THREE_F * vy;
        interpolateTriangleCell(dst, outIdx, tx, ty, p00x, p00y, p10x, p10y, p01x, p01y, p11x, p11y);
    }

    private static void interpolateTriangleCell(
            final float[] dst,
            final int outIdx,
            final float tx,
            final float ty,
            final float p00x,
            final float p00y,
            final float p10x,
            final float p10y,
            final float p01x,
            final float p01y,
            final float p11x,
            final float p11y) {
        if (tx + ty <= ONE_F) {
            dst[outIdx] = (p00x + (p10x - p00x) * tx) + (p01x - p00x) * ty;
            dst[outIdx + Y_OFFSET] = (p00y + (p10y - p00y) * tx) + (p01y - p00y) * ty;
        } else {
            final float invTx = ONE_F - tx;
            final float invTy = ONE_F - ty;
            dst[outIdx] = (p11x + (p01x - p11x) * invTx) + (p10x - p11x) * invTy;
            dst[outIdx + Y_OFFSET] = (p11y + (p01y - p11y) * invTx) + (p10y - p11y) * invTy;
        }
    }

    private static void computeCornerBasis(
            final float[] pt, final int col, final int row, final int colPlus1, final float[] basis) {
        final int p00Idx = ORIGIN_INDEX;
        final int pC0Idx = col << SHIFT_PAIR;
        final int p0RIdx = (row * colPlus1) << SHIFT_PAIR;
        final int pCRIdx = (col + row * colPlus1) << SHIFT_PAIR;

        final float cxBase = QUARTER_F * (((pt[p00Idx] + pt[pC0Idx]) + pt[p0RIdx]) + pt[pCRIdx]);
        final float cyBase = QUARTER_F
                * (((pt[p00Idx + Y_OFFSET] + pt[pC0Idx + Y_OFFSET]) + pt[p0RIdx + Y_OFFSET])
                        + pt[pCRIdx + Y_OFFSET]);
        final float dx1 = pt[pCRIdx] - pt[p00Idx];
        final float dy1 = pt[pCRIdx + Y_OFFSET] - pt[p00Idx + Y_OFFSET];
        final float dx2 = pt[pC0Idx] - pt[p0RIdx];
        final float dy2 = pt[pC0Idx + Y_OFFSET] - pt[p0RIdx + Y_OFFSET];
        final float ux = (dx1 + dx2) * HALF_F;
        final float uy = (dy1 + dy2) * HALF_F;
        final float vx = (dx1 - dx2) * HALF_F;
        final float vy = (dy1 - dy2) * HALF_F;
        basis[BASIS_CX] = cxBase - HALF_F * (ux + vx);
        basis[BASIS_CY] = cyBase - HALF_F * (uy + vy);
        basis[BASIS_UX] = ux;
        basis[BASIS_UY] = uy;
        basis[BASIS_VX] = vx;
        basis[BASIS_VY] = vy;
    }

    private static final class ThreadStats {
        final Thread owner = Thread.currentThread();
        final float[] basis = new float[BASIS_LENGTH];
        long oobTailCalls;
        long oobPoints;
        long inBoundsFallbackPoints;
        long failures;
    }
}
