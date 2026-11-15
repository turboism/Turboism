package dev.turboism.adapter.cubism.optimization.warpgrid;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.reflect.HostHandles.HostField;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Publishes runtime gates and out-of-bounds tail handling for warp grid interpolation.
 *
 * <p>The woven host method executes the in-bounds fast path without allocations and invokes the
 * bound {@link MethodHandle} only when a point exits grid bounds. Keeping out-of-bounds math in
 * specialized Java loops reduces host bytecode pressure and preserves IEEE-754 bit parity.</p>
 */
public final class WarpGridInterpolationBridge implements AutoCloseable {

    /** Startup toggle property for warp grid interpolation fast path. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.warpGridInterpolation";

    /** System property slot holding the {@link AtomicBoolean} diagnostic gate. */
    public static final String ACTIVE_PROPERTY = "turboism.warp-grid-interpolation.active";

    /** System property slot holding the {@link MethodHandle} out-of-bounds tail handler. */
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

    private static final long ZERO_COUNT = 0L;
    private static final long BOUND_PRESENT = 1L;
    private static final long BOUND_ABSENT = 0L;

    private static final String OOB_HANDLER_METHOD = "handleOobTail";
    private static final MethodType OOB_HANDLER_TYPE = MethodType.methodType(
            void.class,
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
    private final AtomicLong oobTailCalls = new AtomicLong();
    private final AtomicLong oobPoints = new AtomicLong();
    private final AtomicLong inBoundsFallbackPoints = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
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
        final Properties props = System.getProperties();
        props.remove(ACTIVE_PROPERTY, active);
        props.remove(OOB_PROPERTY, oobHandle);
        props.remove(STATS_PROPERTY, statsSupplier);
    }

    /** Returns an immutable counters snapshot for diagnostics. */
    public Map<String, Long> snapshot() {
        return Map.of(
                STAT_OOB_TAIL_CALLS, oobTailCalls.get(),
                STAT_OOB_POINTS, oobPoints.get(),
                STAT_IN_BOUNDS_FALLBACK_POINTS, inBoundsFallbackPoints.get(),
                STAT_FAILURES, failures.get(),
                STAT_BOUND, isSingletonBound() ? BOUND_PRESENT : BOUND_ABSENT);
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
    private void handleOobTail(
            final float[] src,
            final float[] dst,
            final int startIndex,
            final int limit,
            final int srcStep,
            final float[] pt,
            final int col,
            final int row,
            final boolean isQuad) {
        try {
            transformTail(src, dst, startIndex, limit, srcStep, pt, col, row, isQuad);
        } catch (final Throwable t) {
            FatalErrors.rethrowIfFatal(t);
            failures.incrementAndGet();
            if (t instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (t instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException(OOB_HANDLER_FAILED_ERROR, t);
        }
    }

    /**
     * Completes transformation from the first out-of-bounds index with strict IEEE-754 order.
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
        if (isQuad) {
            transformTailQuad(src, dst, startIndex, limit, srcStep, pt, col, row);
        } else {
            transformTailTriangle(src, dst, startIndex, limit, srcStep, pt, col, row);
        }
    }

    private void transformTailQuad(
            final float[] src,
            final float[] dst,
            final int startIndex,
            final int limit,
            final int srcStep,
            final float[] pt,
            final int col,
            final int row) {
        final int colPlus1 = col + CELL_STEP;
        final int rowStride = colPlus1 << SHIFT_PAIR;
        final float fCol = (float) col;
        final float fRow = (float) row;

        CornerBasis basis = null;
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
            if (basis == null) {
                basis = CornerBasis.compute(pt, col, row, colPlus1);
            }
            interpolateOobPoint(dst, i, pt, col, row, colPlus1, fCol, fRow, sx, sy, x, y, basis);
        }

        flushTailCounters(inBoundsCount, oobCount);
    }

    private void transformTailTriangle(
            final float[] src,
            final float[] dst,
            final int startIndex,
            final int limit,
            final int srcStep,
            final float[] pt,
            final int col,
            final int row) {
        final int colPlus1 = col + CELL_STEP;
        final int rowStride = colPlus1 << SHIFT_PAIR;
        final float fCol = (float) col;
        final float fRow = (float) row;

        CornerBasis basis = null;
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
            if (basis == null) {
                basis = CornerBasis.compute(pt, col, row, colPlus1);
            }
            interpolateOobPoint(dst, i, pt, col, row, colPlus1, fCol, fRow, sx, sy, x, y, basis);
        }

        flushTailCounters(inBoundsCount, oobCount);
    }

    private void flushTailCounters(final long inBoundsCount, final long oobCount) {
        oobTailCalls.incrementAndGet();
        if (inBoundsCount != ZERO_COUNT) {
            inBoundsFallbackPoints.addAndGet(inBoundsCount);
        }
        if (oobCount != ZERO_COUNT) {
            oobPoints.addAndGet(oobCount);
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
            final CornerBasis basis) {
        if (sx > NEG_TWO_F && sx < THREE_F && sy > NEG_TWO_F && sy < THREE_F) {
            interpolateBorderBand(dst, outIdx, pt, col, row, colPlus1, fCol, fRow, sx, sy, x, y, basis);
        } else {
            dst[outIdx] = (basis.cx + sx * basis.ux) + sy * basis.vx;
            dst[outIdx + Y_OFFSET] = (basis.cy + sx * basis.uy) + sy * basis.vy;
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
            final CornerBasis basis) {
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
            final CornerBasis basis) {
        if (sy <= ZERO_F) {
            final float p11x = pt[ORIGIN_INDEX];
            final float p11y = pt[Y_OFFSET];
            final float p01x = basis.cx - TWO_F * basis.ux;
            final float p01y = basis.cy - TWO_F * basis.uy;
            final float p10x = basis.cx - TWO_F * basis.vx;
            final float p10y = basis.cy - TWO_F * basis.vy;
            final float p00x = p01x - TWO_F * basis.vx;
            final float p00y = p01y - TWO_F * basis.vy;
            final float tx = HALF_F * (sx - NEG_TWO_F);
            final float ty = HALF_F * (sy - NEG_TWO_F);
            interpolateTriangleCell(dst, outIdx, tx, ty, p00x, p00y, p10x, p10y, p01x, p01y, p11x, p11y);
        } else if (sy >= ONE_F) {
            final int rowBase = (row * colPlus1) << SHIFT_PAIR;
            final float p10x = pt[rowBase];
            final float p10y = pt[rowBase + Y_OFFSET];
            final float p00x = (basis.cx - TWO_F * basis.ux) + ONE_F * basis.vx;
            final float p00y = (basis.cy - TWO_F * basis.uy) + ONE_F * basis.vy;
            final float p11x = basis.cx + THREE_F * basis.vx;
            final float p11y = basis.cy + THREE_F * basis.vy;
            final float p01x = (basis.cx - TWO_F * basis.ux) + THREE_F * basis.vx;
            final float p01y = (basis.cy - TWO_F * basis.uy) + THREE_F * basis.vy;
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
            final float p00x = (basis.cx - TWO_F * basis.ux) + t0 * basis.vx;
            final float p00y = (basis.cy - TWO_F * basis.uy) + t0 * basis.vy;
            final float p01x = (basis.cx - TWO_F * basis.ux) + t1 * basis.vx;
            final float p01y = (basis.cy - TWO_F * basis.uy) + t1 * basis.vy;
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
            final CornerBasis basis) {
        if (sy <= ZERO_F) {
            final int cIdx = col << SHIFT_PAIR;
            final float p01x = pt[cIdx];
            final float p01y = pt[cIdx + Y_OFFSET];
            final float p11x = basis.cx + THREE_F * basis.ux;
            final float p11y = basis.cy + THREE_F * basis.uy;
            final float p00x = (basis.cx + ONE_F * basis.ux) - TWO_F * basis.vx;
            final float p00y = (basis.cy + ONE_F * basis.uy) - TWO_F * basis.vy;
            final float p10x = p11x - TWO_F * basis.vx;
            final float p10y = p11y - TWO_F * basis.vy;
            final float tx = HALF_F * (sx - ONE_F);
            final float ty = HALF_F * (sy - NEG_TWO_F);
            interpolateTriangleCell(dst, outIdx, tx, ty, p00x, p00y, p10x, p10y, p01x, p01y, p11x, p11y);
        } else if (sy >= ONE_F) {
            final int crIdx = (col + row * colPlus1) << SHIFT_PAIR;
            final float p00x = pt[crIdx];
            final float p00y = pt[crIdx + Y_OFFSET];
            final float p10x = (basis.cx + THREE_F * basis.ux) + ONE_F * basis.vx;
            final float p10y = (basis.cy + THREE_F * basis.uy) + ONE_F * basis.vy;
            final float p01x = (basis.cx + ONE_F * basis.ux) + THREE_F * basis.vx;
            final float p01y = (basis.cy + ONE_F * basis.uy) + THREE_F * basis.vy;
            final float p11x = (basis.cx + THREE_F * basis.ux) + THREE_F * basis.vx;
            final float p11y = (basis.cy + THREE_F * basis.uy) + THREE_F * basis.vy;
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
            final float p10x = (basis.cx + THREE_F * basis.ux) + t0 * basis.vx;
            final float p10y = (basis.cy + THREE_F * basis.uy) + t0 * basis.vy;
            final float p11x = (basis.cx + THREE_F * basis.ux) + t1 * basis.vx;
            final float p11y = (basis.cy + THREE_F * basis.uy) + t1 * basis.vy;
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
            final CornerBasis basis) {
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
        final float p00x = (basis.cx + s0 * basis.ux) - TWO_F * basis.vx;
        final float p00y = (basis.cy + s0 * basis.uy) - TWO_F * basis.vy;
        final float p10x = (basis.cx + s1 * basis.ux) - TWO_F * basis.vx;
        final float p10y = (basis.cy + s1 * basis.uy) - TWO_F * basis.vy;
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
            final CornerBasis basis) {
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
        final float p01x = (basis.cx + s0 * basis.ux) + THREE_F * basis.vx;
        final float p01y = (basis.cy + s0 * basis.uy) + THREE_F * basis.vy;
        final float p11x = (basis.cx + s1 * basis.ux) + THREE_F * basis.vx;
        final float p11y = (basis.cy + s1 * basis.uy) + THREE_F * basis.vy;
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

    private record CornerBasis(float cx, float cy, float ux, float uy, float vx, float vy) {
        private static CornerBasis compute(final float[] pt, final int col, final int row, final int colPlus1) {
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
            final float cx = cxBase - HALF_F * (ux + vx);
            final float cy = cyBase - HALF_F * (uy + vy);
            return new CornerBasis(cx, cy, ux, uy, vx, vy);
        }
    }
}
