package dev.turboism.adapter.cubism.optimization.warpgrid;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.reflect.HostHandles.HostField;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Publishes runtime gates and out-of-bounds tail handling for warp grid interpolation.
 *
 * <p>The woven host method executes the in-bounds fast path without allocations and invokes this
 * bridge only when a point exits the grid bounds. Keeping out-of-bounds math in pure Java reduces
 * bytecode size and preserves exact IEEE-754 floating-point evaluation order.</p>
 */
public final class WarpGridInterpolationBridge implements AutoCloseable {

    /** Startup toggle property for warp grid interpolation fast path. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.warpGridInterpolation";

    /** System property slot holding the {@link AtomicBoolean} fast-path gate. */
    public static final String ACTIVE_PROPERTY = "turboism.warp-grid-interpolation.active";

    /** System property slot holding the {@link Consumer} out-of-bounds tail handler. */
    public static final String OOB_PROPERTY = "turboism.warp-grid-interpolation.oob";

    /** System property slot holding the {@link Supplier} counters snapshot. */
    public static final String STATS_PROPERTY = "turboism.warp-grid-interpolation.stats";

    /** Payload slot index for input point coordinates. */
    public static final int ARG_SRC = 0;

    /** Payload slot index for output point coordinates. */
    public static final int ARG_DST = 1;

    /** Payload slot index for the first out-of-bounds point index. */
    public static final int ARG_START_INDEX = 2;

    /** Payload slot index for the exclusive loop limit. */
    public static final int ARG_LIMIT = 3;

    /** Payload slot index for the coordinate pair stride. */
    public static final int ARG_SRC_STEP = 4;

    /** Payload slot index for grid control positions. */
    public static final int ARG_GRID_POINTS = 5;

    /** Payload slot index for grid column count. */
    public static final int ARG_COL = 6;

    /** Payload slot index for grid row count. */
    public static final int ARG_ROW = 7;

    /** Payload slot index for quad interpolation mode flag. */
    public static final int ARG_QUAD = 8;

    /** Fixed payload array length passed to {@link #OOB_PROPERTY}. */
    public static final int ARG_LENGTH = 9;

    private static final float ZERO_F = 0.0f;
    private static final float ONE_F = 1.0f;
    private static final float TWO_F = 2.0f;
    private static final float THREE_F = 3.0f;
    private static final float NEG_TWO_F = -2.0f;
    private static final float HALF_F = 0.5f;
    private static final float QUARTER_F = 0.25f;

    private static final int COORD_COMPONENTS = 2;
    private static final int SHIFT_PAIR = 1;
    private static final int Y_OFFSET = 1;
    private static final int NEXT_X_OFFSET = 2;
    private static final int NEXT_Y_OFFSET = 3;

    private static final String ERROR_CALC_FORMAT = "error calc : %.4f , %.4f\t\t\t\t\t@@BDBoxGrid\n";
    private static final String STAT_OOB_TAIL_CALLS = "oobTailCalls";
    private static final String STAT_OOB_POINTS = "oobPoints";
    private static final String STAT_IN_BOUNDS_FALLBACK_POINTS = "inBoundsFallbackPoints";
    private static final String STAT_FAILURES = "failures";
    private static final String STAT_BOUND = "bound";

    private final Class<?> utilsClass;
    private final HostField singletonField;
    private final MethodHandle transformHandle;
    private final AtomicBoolean active = new AtomicBoolean(true);
    private final AtomicLong oobTailCalls = new AtomicLong();
    private final AtomicLong oobPoints = new AtomicLong();
    private final AtomicLong inBoundsFallbackPoints = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final Consumer<Object[]> oobConsumer = this::handleOobPayload;
    private final Supplier<Map<String, Long>> statsSupplier = this::snapshot;

    private WarpGridInterpolationBridge(
            final Class<?> utilsClass,
            final HostField singletonField,
            final MethodHandle transformHandle) {
        this.utilsClass = Objects.requireNonNull(utilsClass, "utilsClass");
        this.singletonField = Objects.requireNonNull(singletonField, "singletonField");
        this.transformHandle = Objects.requireNonNull(transformHandle, "transformHandle");
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
            final HostField singletonField =
                    HostHandles.field(utilsClass, WarpGridInterpolationTarget.SINGLETON_FIELD);
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
            throw new IllegalStateException("failed to bind warp grid interpolation host handles", t);
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
        props.put(OOB_PROPERTY, oobConsumer);
        props.put(STATS_PROPERTY, statsSupplier);
    }

    /** Disables the fast-path gate and removes published slots when unchanged. */
    @Override
    public void close() {
        active.set(false);
        final Properties props = System.getProperties();
        props.remove(ACTIVE_PROPERTY, active);
        props.remove(OOB_PROPERTY, oobConsumer);
        props.remove(STATS_PROPERTY, statsSupplier);
    }

    /** Returns an immutable counters snapshot for diagnostics. */
    public Map<String, Long> snapshot() {
        return Map.of(
                STAT_OOB_TAIL_CALLS, oobTailCalls.get(),
                STAT_OOB_POINTS, oobPoints.get(),
                STAT_IN_BOUNDS_FALLBACK_POINTS, inBoundsFallbackPoints.get(),
                STAT_FAILURES, failures.get(),
                STAT_BOUND, isSingletonBound() ? 1L : 0L);
    }

    private boolean isSingletonBound() {
        try {
            return singletonField.get(null) != null;
        } catch (final Throwable ignored) {
            return false;
        }
    }

    private void handleOobPayload(final Object[] args) {
        if (args == null || args.length != ARG_LENGTH) {
            failures.incrementAndGet();
            throw new IllegalArgumentException("invalid warp grid OOB payload length");
        }
        try {
            oobTailCalls.incrementAndGet();
            final float[] src = (float[]) args[ARG_SRC];
            final float[] dst = (float[]) args[ARG_DST];
            final int startIndex = (Integer) args[ARG_START_INDEX];
            final int limit = (Integer) args[ARG_LIMIT];
            final int srcStep = (Integer) args[ARG_SRC_STEP];
            final float[] pt = (float[]) args[ARG_GRID_POINTS];
            final int col = (Integer) args[ARG_COL];
            final int row = (Integer) args[ARG_ROW];
            final boolean isQuad = (Boolean) args[ARG_QUAD];
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
            throw new IllegalStateException("warp grid out-of-bounds handler failed", t);
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
        final int colPlus1 = col + 1;
        final int rowStride = colPlus1 << SHIFT_PAIR;
        final float fCol = (float) col;
        final float fRow = (float) row;

        boolean initialized = false;
        float cx = ZERO_F;
        float cy = ZERO_F;
        float ux = ZERO_F;
        float uy = ZERO_F;
        float vx = ZERO_F;
        float vy = ZERO_F;

        for (int i = startIndex; i < limit; i += srcStep) {
            final float sx = src[i];
            final float sy = src[i + Y_OFFSET];
            final float x = sx * fCol;
            final float y = sy * fRow;

            // Keep exact host NaN comparison routing so NaN falls into in-bounds math.
            if (!(x < ZERO_F) && !(y < ZERO_F) && !(fCol <= x) && !(fRow <= y)) {
                inBoundsFallbackPoints.incrementAndGet();
                interpolateInBounds(dst, i, pt, x, y, colPlus1, rowStride, isQuad);
                continue;
            }

            oobPoints.incrementAndGet();
            if (!initialized) {
                initialized = true;
                final int p00Idx = 0;
                final int pC0Idx = col << SHIFT_PAIR;
                final int p0RIdx = (row * colPlus1) << SHIFT_PAIR;
                final int pCRIdx = (col + row * colPlus1) << SHIFT_PAIR;

                cx = QUARTER_F * (((pt[p00Idx] + pt[pC0Idx]) + pt[p0RIdx]) + pt[pCRIdx]);
                cy = QUARTER_F
                        * (((pt[p00Idx + Y_OFFSET] + pt[pC0Idx + Y_OFFSET]) + pt[p0RIdx + Y_OFFSET])
                                + pt[pCRIdx + Y_OFFSET]);
                final float dx1 = pt[pCRIdx] - pt[p00Idx];
                final float dy1 = pt[pCRIdx + Y_OFFSET] - pt[p00Idx + Y_OFFSET];
                final float dx2 = pt[pC0Idx] - pt[p0RIdx];
                final float dy2 = pt[pC0Idx + Y_OFFSET] - pt[p0RIdx + Y_OFFSET];
                ux = (dx1 + dx2) * HALF_F;
                uy = (dy1 + dy2) * HALF_F;
                vx = (dx1 - dx2) * HALF_F;
                vy = (dy1 - dy2) * HALF_F;
                cx = cx - HALF_F * (ux + vx);
                cy = cy - HALF_F * (uy + vy);
            }

            if (sx > NEG_TWO_F && sx < THREE_F && sy > NEG_TWO_F && sy < THREE_F) {
                interpolateBorderBand(dst, i, pt, col, row, colPlus1, sx, sy, x, y, cx, cy, ux, uy, vx, vy);
            } else {
                dst[i] = (cx + sx * ux) + sy * vx;
                dst[i + Y_OFFSET] = (cy + sx * uy) + sy * vy;
            }
        }
    }

    private static void interpolateInBounds(
            final float[] dst,
            final int outIdx,
            final float[] pt,
            final float x,
            final float y,
            final int colPlus1,
            final int rowStride,
            final boolean isQuad) {
        final int xi = (int) x;
        final int yi = (int) y;
        final float xRate = x - (float) xi;
        final float yRate = y - (float) yi;
        final int index = (xi + yi * colPlus1) << SHIFT_PAIR;
        final int nextRow = index + rowStride;

        if (isQuad) {
            final float invX = ONE_F - xRate;
            final float invY = ONE_F - yRate;
            dst[outIdx] = ((pt[index] * invX) * invY + (pt[index + NEXT_X_OFFSET] * xRate) * invY)
                    + (pt[nextRow] * invX) * yRate
                    + (pt[nextRow + NEXT_X_OFFSET] * xRate) * yRate;
            dst[outIdx + Y_OFFSET] = ((pt[index + Y_OFFSET] * invX) * invY
                            + (pt[index + NEXT_Y_OFFSET] * xRate) * invY)
                    + (pt[nextRow + Y_OFFSET] * invX) * yRate
                    + (pt[nextRow + NEXT_Y_OFFSET] * xRate) * yRate;
        } else if (xRate + yRate < ONE_F) {
            final float w00 = (ONE_F - xRate) - yRate;
            dst[outIdx] = (pt[index] * w00 + pt[index + NEXT_X_OFFSET] * xRate) + pt[nextRow] * yRate;
            dst[outIdx + Y_OFFSET] =
                    (pt[index + Y_OFFSET] * w00 + pt[index + NEXT_Y_OFFSET] * xRate)
                            + pt[nextRow + Y_OFFSET] * yRate;
        } else {
            final float w11 = (xRate - ONE_F) + yRate;
            final float invX = ONE_F - xRate;
            final float invY = ONE_F - yRate;
            dst[outIdx] = (pt[nextRow + NEXT_X_OFFSET] * w11 + pt[nextRow] * invX)
                    + pt[index + NEXT_X_OFFSET] * invY;
            dst[outIdx + Y_OFFSET] = (pt[nextRow + NEXT_Y_OFFSET] * w11 + pt[nextRow + Y_OFFSET] * invX)
                    + pt[index + NEXT_Y_OFFSET] * invY;
        }
    }

    private static void interpolateBorderBand(
            final float[] dst,
            final int outIdx,
            final float[] pt,
            final int col,
            final int row,
            final int colPlus1,
            final float sx,
            final float sy,
            final float x,
            final float y,
            final float cx,
            final float cy,
            final float ux,
            final float uy,
            final float vx,
            final float vy) {
        if (sx <= ZERO_F) {
            interpolateLeftBand(dst, outIdx, pt, row, colPlus1, sx, sy, y, cx, cy, ux, uy, vx, vy);
        } else if (sx >= ONE_F) {
            interpolateRightBand(dst, outIdx, pt, col, row, colPlus1, sx, sy, y, cx, cy, ux, uy, vx, vy);
        } else if (sy <= ZERO_F) {
            interpolateBottomStrip(dst, outIdx, pt, col, sx, sy, x, cx, cy, ux, uy, vx, vy);
        } else if (sy >= ONE_F) {
            interpolateTopStrip(dst, outIdx, pt, col, row, colPlus1, sx, sy, x, cx, cy, ux, uy, vx, vy);
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
            final float sx,
            final float sy,
            final float y,
            final float cx,
            final float cy,
            final float ux,
            final float uy,
            final float vx,
            final float vy) {
        if (sy <= ZERO_F) {
            final float p11x = pt[0];
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
                iy = row - 1;
            }
            final float tx = HALF_F * (sx - NEG_TWO_F);
            final float ty = y - (float) iy;
            final float t0 = (float) iy / (float) row;
            final float t1 = (float) (iy + 1) / (float) row;
            final int idx0 = (iy * colPlus1) << SHIFT_PAIR;
            final int idx1 = ((iy + 1) * colPlus1) << SHIFT_PAIR;
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
            final float sx,
            final float sy,
            final float y,
            final float cx,
            final float cy,
            final float ux,
            final float uy,
            final float vx,
            final float vy) {
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
                iy = row - 1;
            }
            final float tx = HALF_F * (sx - ONE_F);
            final float ty = y - (float) iy;
            final float t0 = (float) iy / (float) row;
            final float t1 = (float) (iy + 1) / (float) row;
            final int idx0 = (col + iy * colPlus1) << SHIFT_PAIR;
            final int idx1 = (col + (iy + 1) * colPlus1) << SHIFT_PAIR;
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
            final float sx,
            final float sy,
            final float x,
            final float cx,
            final float cy,
            final float ux,
            final float uy,
            final float vx,
            final float vy) {
        int ix = (int) x;
        if (ix == col) {
            ix = col - 1;
        }
        final float tx = x - (float) ix;
        final float ty = HALF_F * (sy - NEG_TWO_F);
        final float s0 = (float) ix / (float) col;
        final float s1 = (float) (ix + 1) / (float) col;
        final int idx0 = ix << SHIFT_PAIR;
        final int idx1 = (ix + 1) << SHIFT_PAIR;
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
            final float sx,
            final float sy,
            final float x,
            final float cx,
            final float cy,
            final float ux,
            final float uy,
            final float vx,
            final float vy) {
        int ix = (int) x;
        if (ix == col) {
            ix = col - 1;
        }
        final float tx = x - (float) ix;
        final float ty = HALF_F * (sy - ONE_F);
        final float s0 = (float) ix / (float) col;
        final float s1 = (float) (ix + 1) / (float) col;
        final int rowBase = row * colPlus1;
        final int idx0 = (ix + rowBase) << SHIFT_PAIR;
        final int idx1 = (ix + 1 + rowBase) << SHIFT_PAIR;
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
}
