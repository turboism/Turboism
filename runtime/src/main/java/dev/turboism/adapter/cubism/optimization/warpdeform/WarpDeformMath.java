package dev.turboism.adapter.cubism.optimization.warpdeform;

/**
 * Allocation-free primitive math kernels for warp grid inversion, Bezier subdivision, and overlay triangulation.
 *
 * <p>Reproduces Cubism's floating-point and double-precision formulas without temporary {@code GVector2},
 * {@code ClosedFloatingPointRange}, {@code Double}, {@code Triple}, {@code ArrayList}, or {@code CBox}
 * allocations.</p>
 */
public final class WarpDeformMath {

    private static final double EPSILON = 1.0e-5;
    private static final float ZERO_F = 0.0f;
    private static final float ONE_F = 1.0f;
    private static final float TWO_F = 2.0f;
    private static final float THREE_F = 3.0f;
    private static final float HALF_F = 0.5f;
    private static final float TEN_F = 10.0f;
    private static final double ZERO_D = 0.0d;
    private static final double ONE_D = 1.0d;
    private static final double HALF_D = 0.5d;
    private static final double TWO_D = 2.0d;
    private static final double FOUR_D = 4.0d;
    private static final int COORD_STRIDE = 2;

    private static final ThreadLocal<float[]> BEZIER_LATTICE_SCRATCH = new ThreadLocal<>();

    private WarpDeformMath() {}

    /**
     * Computes horizontal and vertical subdivision counts matching {@code bezier.a} and {@code CBezierGrid}.
     *
     * @param nwX top-left anchor X
     * @param nwY top-left anchor Y
     * @param nwCeX top-left east handle X
     * @param nwCeY top-left east handle Y
     * @param nwCsX top-left south handle X
     * @param nwCsY top-left south handle Y
     * @param neX top-right anchor X
     * @param neY top-right anchor Y
     * @param neCwX top-right west handle X
     * @param neCwY top-right west handle Y
     * @param neCsX top-right south handle X
     * @param neCsY top-right south handle Y
     * @param swX bottom-left anchor X
     * @param swY bottom-left anchor Y
     * @param swCeX bottom-left east handle X
     * @param swCeY bottom-left east handle Y
     * @param swCnX bottom-left north handle X
     * @param swCnY bottom-left north handle Y
     * @param seX bottom-right anchor X
     * @param seY bottom-right anchor Y
     * @param seCwX bottom-right west handle X
     * @param seCwY bottom-right west handle Y
     * @param seCnX bottom-right north handle X
     * @param seCnY bottom-right north handle Y
     * @param offsetPow logarithmic subdivision bias from {@code CBezierGrid.OFF_SET}
     * @param minDiv minimum division count from {@code CBezierGrid.MIN_DIV}
     * @param maxDiv maximum division count from {@code CBezierGrid.MAX_DIV}
     * @return packed division counts {@code (divX << 16) | divY}
     */
    public static int computeCellDivisions(
            final float nwX,
            final float nwY,
            final float nwCeX,
            final float nwCeY,
            final float nwCsX,
            final float nwCsY,
            final float neX,
            final float neY,
            final float neCwX,
            final float neCwY,
            final float neCsX,
            final float neCsY,
            final float swX,
            final float swY,
            final float swCeX,
            final float swCeY,
            final float swCnX,
            final float swCnY,
            final float seX,
            final float seY,
            final float seCwX,
            final float seCwY,
            final float seCnX,
            final float seCnY,
            final int offsetPow,
            final int minDiv,
            final int maxDiv) {
        final float chordTopSq = distSq(nwX, nwY, neX, neY);
        final float chordBotSq = distSq(swX, swY, seX, seY);
        final float devTop = maxCurveDeviationSq(nwX, nwY, nwCeX, nwCeY, neCwX, neCwY, neX, neY);
        final float devBot = maxCurveDeviationSq(swX, swY, swCeX, swCeY, seCwX, seCwY, seX, seY);
        final float flatX = (float) Math.sqrt(Math.max(devTop / chordTopSq, devBot / chordBotSq));

        final float chordLeftSq = distSq(nwX, nwY, swX, swY);
        final float chordRightSq = distSq(neX, neY, seX, seY);
        final float devLeft = maxCurveDeviationSq(nwX, nwY, nwCsX, nwCsY, swCnX, swCnY, swX, swY);
        final float devRight = maxCurveDeviationSq(neX, neY, neCsX, neCsY, seCnX, seCnY, seX, seY);
        final float flatY = (float) Math.sqrt(Math.max(devLeft / chordLeftSq, devRight / chordRightSq));

        final int divX = calcDivFromFlatness(flatX, offsetPow, minDiv, maxDiv);
        final int divY = calcDivFromFlatness(flatY, offsetPow, minDiv, maxDiv);
        return (divX << 16) | (divY & 0xFFFF);
    }

    /**
     * Builds the `(divX + 1) * (divY + 1) * 2` corner lattice for one Bezier patch into reusable scratch.
     *
     * <p>Matches {@code bezier.d.b(...)} float arithmetic and Coons-like interior control projection order.</p>
     *
     * @return thread-local scratch array containing row-major XY corners `(row * (divX + 1) + col)`
     */
    public static float[] buildPatchCornerLattice(
            final float nwX,
            final float nwY,
            final float nwCeX,
            final float nwCeY,
            final float nwCsX,
            final float nwCsY,
            final float neX,
            final float neY,
            final float neCwX,
            final float neCwY,
            final float neCsX,
            final float neCsY,
            final float swX,
            final float swY,
            final float swCeX,
            final float swCeY,
            final float swCnX,
            final float swCnY,
            final float seX,
            final float seY,
            final float seCwX,
            final float seCwY,
            final float seCnX,
            final float seCnY,
            final int divX,
            final int divY) {
        final int stride = divX + 1;
        final int needed = stride * (divY + 1) * COORD_STRIDE;
        float[] lattice = BEZIER_LATTICE_SCRATCH.get();
        if (lattice == null || lattice.length < needed) {
            lattice = new float[needed];
            BEZIER_LATTICE_SCRATCH.set(lattice);
        }

        final float nwNeDist = dist(nwX, nwY, neX, neY);
        final float swSeDist = dist(swX, swY, seX, seY);
        final float wVecX0 = nwCeX - nwX;
        final float wVecY0 = nwCeY - nwY;
        final float wVecX1 = swCeX - swX;
        final float wVecY1 = swCeY - swY;
        final float eVecX0 = neCwX - neX;
        final float eVecY0 = neCwY - neY;
        final float eVecX1 = seCwX - seX;
        final float eVecY1 = seCwY - seY;

        float leftXPrev = evalCubic(nwX, nwCsX, swCnX, swX, ZERO_F);
        float leftYPrev = evalCubic(nwY, nwCsY, swCnY, swY, ZERO_F);
        float rightXPrev = evalCubic(neX, neCsX, seCnX, seX, ZERO_F);
        float rightYPrev = evalCubic(neY, neCsY, seCnY, seY, ZERO_F);

        for (int iy = 0; iy < divY; iy++) {
            final float v0 = (float) iy / (float) divY;
            final float v1 = (float) (iy + 1) / (float) divY;
            final float leftXNext = evalCubic(nwX, nwCsX, swCnX, swX, v1);
            final float leftYNext = evalCubic(nwY, nwCsY, swCnY, swY, v1);
            final float rightXNext = evalCubic(neX, neCsX, seCnX, seX, v1);
            final float rightYNext = evalCubic(neY, neCsY, seCnY, seY, v1);

            final float d0 = dist(leftXPrev, leftYPrev, rightXPrev, rightYPrev);
            final float d1 = dist(leftXNext, leftYNext, rightXNext, rightYNext);
            final float scale0 = d0 / (nwNeDist * (ONE_F - v0) + swSeDist * v0);
            final float scale1 = d1 / (nwNeDist * (ONE_F - v1) + swSeDist * v1);

            final float c1TopX = leftXPrev + (wVecX0 + (wVecX1 - wVecX0) * v0) * scale0;
            final float c1TopY = leftYPrev + (wVecY0 + (wVecY1 - wVecY0) * v0) * scale0;
            // Match bezier.d control evaluation order using rightXPrev/rightYPrev.
            final float ctrl2TopX = rightXPrev + (eVecX0 + (eVecX1 - eVecX0) * v0) * scale0;
            final float ctrl2TopY = rightYPrev + (eVecY0 + (eVecY1 - eVecY0) * v0) * scale0;

            final float c1BotX = leftXNext + (wVecX0 + (wVecX1 - wVecX0) * v1) * scale1;
            final float c1BotY = leftYNext + (wVecY0 + (wVecY1 - wVecY0) * v1) * scale1;
            final float ctrl2BotX = rightXNext + (eVecX0 + (eVecX1 - eVecX0) * v1) * scale1;
            final float ctrl2BotY = rightYNext + (eVecY0 + (eVecY1 - eVecY0) * v1) * scale1;
            final int basePrev = iy * stride * COORD_STRIDE;
            final int baseNext = (iy + 1) * stride * COORD_STRIDE;

            lattice[basePrev] = evalCubic(leftXPrev, c1TopX, ctrl2TopX, rightXPrev, ZERO_F);
            lattice[basePrev + 1] = evalCubic(leftYPrev, c1TopY, ctrl2TopY, rightYPrev, ZERO_F);
            lattice[baseNext] = evalCubic(leftXNext, c1BotX, ctrl2BotX, rightXNext, ZERO_F);
            lattice[baseNext + 1] = evalCubic(leftYNext, c1BotY, ctrl2BotY, rightYNext, ZERO_F);

            for (int ix = 0; ix < divX; ix++) {
                final float u1 = (float) (ix + 1) / (float) divX;
                final int colOff = (ix + 1) * COORD_STRIDE;
                lattice[basePrev + colOff] = evalCubic(leftXPrev, c1TopX, ctrl2TopX, rightXPrev, u1);
                lattice[basePrev + colOff + 1] = evalCubic(leftYPrev, c1TopY, ctrl2TopY, rightYPrev, u1);
                lattice[baseNext + colOff] = evalCubic(leftXNext, c1BotX, ctrl2BotX, rightXNext, u1);
                lattice[baseNext + colOff + 1] = evalCubic(leftYNext, c1BotY, ctrl2BotY, rightYNext, u1);
            }

            leftXPrev = leftXNext;
            leftYPrev = leftYNext;
            rightXPrev = rightXNext;
            rightYPrev = rightYNext;
        }
        return lattice;
    }

    /**
     * Evaluates a local `(u, v)` coordinate against the lattice built by {@link #buildPatchCornerLattice}.
     *
     * @param lattice corner lattice produced by {@link #buildPatchCornerLattice}
     * @param divX patch X division count
     * @param divY patch Y division count
     * @param lu patch-local horizontal coordinate in {@code [0, 1]}
     * @param lv patch-local vertical coordinate in {@code [0, 1]}
     * @param out destination float buffer
     * @param outOffset offset into {@code out}
     */
    public static void evaluatePatchLatticePoint(
            final float[] lattice,
            final int divX,
            final int divY,
            final float lu,
            final float lv,
            final float[] out,
            final int outOffset) {
        int sx = (int) (lu * divX);
        if (sx < 0) sx = 0;
        else if (sx >= divX) sx = divX - 1;

        int sy = (int) (lv * divY);
        if (sy < 0) sy = 0;
        else if (sy >= divY) sy = divY - 1;

        float su = lu * divX - sx;
        if (su < ZERO_F) su = ZERO_F;
        else if (su > ONE_F) su = ONE_F;

        float sv = lv * divY - sy;
        if (sv < ZERO_F) sv = ZERO_F;
        else if (sv > ONE_F) sv = ONE_F;

        final int stride = divX + 1;
        final int i00 = (sy * stride + sx) * COORD_STRIDE;
        final int i10 = i00 + COORD_STRIDE;
        final int i01 = i00 + stride * COORD_STRIDE;
        final int i11 = i01 + COORD_STRIDE;

        if (su + sv <= ONE_F) {
            final float nwx = lattice[i00];
            final float nwy = lattice[i00 + 1];
            out[outOffset] = nwx + (lattice[i10] - nwx) * su + (lattice[i01] - nwx) * sv;
            out[outOffset + 1] = nwy + (lattice[i10 + 1] - nwy) * su + (lattice[i01 + 1] - nwy) * sv;
        } else {
            final float sex = lattice[i11];
            final float sey = lattice[i11 + 1];
            final float invU = ONE_F - su;
            final float invV = ONE_F - sv;
            out[outOffset] = sex + (lattice[i01] - sex) * invU + (lattice[i10] - sex) * invV;
            out[outOffset + 1] = sey + (lattice[i01 + 1] - sey) * invU + (lattice[i10 + 1] - sey) * invV;
        }
    }

    /**
     * Scans a simple grid of quad cells to find normalized `(u, v)` coordinates for `(x, y)`.
     *
     * <p>Matches {@code CSimpleGrid.get01_ex_quad} exact scan order and optional nearest-target mode
     * with zero heap allocations.</p>
     *
     * @param points grid vertex array `(row * (col + 1) + ix) * 2`
     * @param col grid column count
     * @param row grid row count
     * @param x query X coordinate
     * @param y query Y coordinate
     * @param targetX preferred normalized X when {@code hasTarget} is true
     * @param targetY preferred normalized Y when {@code hasTarget} is true
     * @param hasTarget whether multiple hits pick the candidate closest to {@code (targetX, targetY)}
     * @param out destination length-2 float array
     * @return {@code out} when inside any grid quad, otherwise {@code null}
     */
    public static float[] get01ExQuad(
            final float[] points,
            final int col,
            final int row,
            final float x,
            final float y,
            final float targetX,
            final float targetY,
            final boolean hasTarget,
            final float[] out) {
        if (col <= 0 || row <= 0 || points == null) {
            return null;
        }
        final int stride = col + 1;
        if (points.length < stride * (row + 1) * COORD_STRIDE) {
            return null;
        }
        float bestU = Float.NaN;
        float bestV = ZERO_F;
        float bestDistSq = ZERO_F;

        for (int iy = 0; iy < row; iy++) {
            final int rowBase = iy * stride;
            final int nextRowBase = rowBase + stride;
            for (int ix = 0; ix < col; ix++) {
                final int i00 = (rowBase + ix) * COORD_STRIDE;
                final int i10 = i00 + COORD_STRIDE;
                final int i01 = (nextRowBase + ix) * COORD_STRIDE;
                final int i11 = i01 + COORD_STRIDE;

                final float x0 = points[i00];
                final float y0 = points[i00 + 1];
                final float x1 = points[i10];
                final float y1 = points[i10 + 1];
                final float x2 = points[i01];
                final float y2 = points[i01 + 1];
                final float x3 = points[i11];
                final float y3 = points[i11 + 1];

                // Fast AABB rejection before solving bilinear inverse.
                float minX = x0;
                if (x1 < minX) minX = x1;
                if (x2 < minX) minX = x2;
                if (x3 < minX) minX = x3;
                if (x < minX) continue;

                float maxX = x0;
                if (x1 > maxX) maxX = x1;
                if (x2 > maxX) maxX = x2;
                if (x3 > maxX) maxX = x3;
                if (x > maxX) continue;

                float minY = y0;
                if (y1 < minY) minY = y1;
                if (y2 < minY) minY = y2;
                if (y3 < minY) minY = y3;
                if (y < minY) continue;

                float maxY = y0;
                if (y1 > maxY) maxY = y1;
                if (y2 > maxY) maxY = y2;
                if (y3 > maxY) maxY = y3;
                if (y > maxY) continue;

                if (!solveBilinearQuad(x0, y0, x1, y1, x2, y2, x3, y3, x, y, out)) {
                    continue;
                }
                final float u = (ix + out[0]) / col;
                final float v = (iy + out[1]) / row;
                if (!hasTarget) {
                    out[0] = u;
                    out[1] = v;
                    return out;
                }
                final float dx = targetX - u;
                final float dy = targetY - v;
                final float distSq = dx * dx + dy * dy;
                if (Float.isNaN(bestU) || distSq < bestDistSq) {
                    bestU = u;
                    bestV = v;
                    bestDistSq = distSq;
                }
            }
        }
        if (Float.isNaN(bestU)) {
            return null;
        }
        out[0] = bestU;
        out[1] = bestV;
        return out;
    }

    /**
     * Solves bilinear cell inverse parameters `(u, v)` in `[-tol, 1 + tol]` matching {@code grid.d.b}.
     *
     * @param x0 corner `(0, 0)` X
     * @param y0 corner `(0, 0)` Y
     * @param x1 corner `(1, 0)` X
     * @param y1 corner `(1, 0)` Y
     * @param x2 corner `(0, 1)` X
     * @param y2 corner `(0, 1)` Y
     * @param x3 corner `(1, 1)` X
     * @param y3 corner `(1, 1)` Y
     * @param px query X
     * @param py query Y
     * @param out length-2 destination array receiving cell-local `(u, v)` on match
     * @return {@code true} when a valid root inside `[-tol, 1 + tol]` is found
     */
    public static boolean solveBilinearQuad(
            final double x0,
            final double y0,
            final double x1,
            final double y1,
            final double x2,
            final double y2,
            final double x3,
            final double y3,
            final double px,
            final double py,
            final float[] out) {
        final double lo = -EPSILON;
        final double hi = ONE_D + EPSILON;

        final double eX = x0 - px;
        final double eY = y0 - py;
        final double bX = x1 - x0;
        final double bY = y1 - y0;
        final double cX = x2 - x0;
        final double cY = y2 - y0;
        final double dX = x3 - x1 - x2 + x0;
        final double dY = y3 - y1 - y2 + y0;

        if (isZero(dX) && isZero(dY)) {
            final double denom = bX * cY - bY * cX;
            if (isZero(denom)) {
                if (isZero(eX * cY - eY * cX) && isZero(eY * bX - eX * bY)) {
                    return solveDegenerateEdges(lo, hi, out, cX, dX, cY, dY, eX, bX, eY, bY);
                }
                return false;
            }
            final double u = -(eX * cY - eY * cX) / denom;
            final double v = -(eY * bX - eX * bY) / denom;
            if (inRange(u, lo, hi) && inRange(v, lo, hi)) {
                out[0] = (float) u;
                out[1] = (float) v;
                return true;
            }
            return false;
        }

        final double k0 = eX * dY - eY * dX;
        final double k1 = bX * dY - bY * dX;
        final double k2 = cX * dY - cY * dX;

        if (isZero(k1) && isZero(k2)) {
            if (isZero(k0)) {
                return solveDegenerateEdges(lo, hi, out, cX, dX, cY, dY, eX, bX, eY, bY);
            }
            return false;
        }

        if (isZero(k1)) {
            final double v = -k0 / k2;
            if (!inRange(v, lo, hi)) {
                return false;
            }
            final double u = solveLinearCoord(bX, dX, bY, dY, eX, cX, eY, cY, v);
            if (inRange(u, lo, hi)) {
                out[0] = (float) u;
                out[1] = (float) v;
                return true;
            }
            if (Double.isNaN(u)) {
                out[0] = HALF_F;
                out[1] = (float) v;
                return true;
            }
            return false;
        }

        if (isZero(k2)) {
            final double u = -k0 / k1;
            if (!inRange(u, lo, hi)) {
                return false;
            }
            final double v = solveLinearCoord(cX, dX, cY, dY, eX, bX, eY, bY, u);
            if (inRange(v, lo, hi)) {
                out[0] = (float) u;
                out[1] = (float) v;
                return true;
            }
            if (Double.isNaN(v)) {
                out[0] = (float) u;
                out[1] = HALF_F;
                return true;
            }
            return false;
        }

        final double s1 = -k2 * dX / k1;
        final double s2 = -k2 * dY / k1;
        final double aCoef;
        final double bCoef;
        final double cCoef;
        if (Math.abs(s1) > Math.abs(s2)) {
            aCoef = s1;
            bCoef = cX - (bX * k2 + dX * k0) / k1;
            cCoef = eX - bX * k0 / k1;
        } else {
            aCoef = s2;
            bCoef = cY - (bY * k2 + dY * k0) / k1;
            cCoef = eY - bY * k0 / k1;
        }

        final double p = bCoef / aCoef;
        final double q = cCoef / aCoef;
        final double disc = p * p - FOUR_D * q;
        if (disc < -EPSILON) {
            return false;
        }
        if (disc <= EPSILON) {
            final double v = -p / TWO_D;
            return tryCandidateV(v, lo, hi, bX, dX, bY, dY, eX, cX, eY, cY, out);
        }
        final double sqrtDisc = Math.sqrt(disc);
        final double v1 = (-p + sqrtDisc) / TWO_D;
        if (tryCandidateV(v1, lo, hi, bX, dX, bY, dY, eX, cX, eY, cY, out)) {
            return true;
        }
        final double v2 = (-p - sqrtDisc) / TWO_D;
        return tryCandidateV(v2, lo, hi, bX, dX, bY, dY, eX, cX, eY, cY, out);
    }

    private static boolean tryCandidateV(
            final double v,
            final double lo,
            final double hi,
            final double bX,
            final double dX,
            final double bY,
            final double dY,
            final double eX,
            final double cX,
            final double eY,
            final double cY,
            final float[] out) {
        if (!inRange(v, lo, hi)) {
            return false;
        }
        final double u = solveLinearCoord(bX, dX, bY, dY, eX, cX, eY, cY, v);
        if (inRange(u, lo, hi)) {
            out[0] = (float) u;
            out[1] = (float) v;
            return true;
        }
        return false;
    }

    private static boolean solveDegenerateEdges(
            final double lo,
            final double hi,
            final float[] out,
            final double cX,
            final double dX,
            final double cY,
            final double dY,
            final double eX,
            final double bX,
            final double eY,
            final double bY) {
        double v0 = solveLinearCoord(cX, dX, cY, dY, eX, bX, eY, bY, ZERO_D);
        if (Double.isNaN(v0)) v0 = HALF_D;
        if (!Double.isInfinite(v0) && inRange(v0, lo, hi)) {
            out[0] = ZERO_F;
            out[1] = (float) v0;
            return true;
        }
        double v1 = solveLinearCoord(cX, dX, cY, dY, eX, bX, eY, bY, ONE_D);
        if (Double.isNaN(v1)) v1 = HALF_D;
        if (!Double.isInfinite(v1) && inRange(v1, lo, hi)) {
            out[0] = ONE_F;
            out[1] = (float) v1;
            return true;
        }
        double u0 = solveLinearCoord(bX, dX, bY, dY, eX, cX, eY, cY, ZERO_D);
        if (Double.isNaN(u0)) u0 = HALF_D;
        if (!Double.isInfinite(u0) && inRange(u0, lo, hi)) {
            out[0] = (float) u0;
            out[1] = ZERO_F;
            return true;
        }
        double u1 = solveLinearCoord(bX, dX, bY, dY, eX, cX, eY, cY, ONE_D);
        if (Double.isNaN(u1)) u1 = HALF_D;
        if (!Double.isInfinite(u1) && inRange(u1, lo, hi)) {
            out[0] = (float) u1;
            out[1] = ONE_F;
            return true;
        }
        return false;
    }

    private static double solveLinearCoord(
            final double a0,
            final double a1,
            final double b0,
            final double b1,
            final double c0,
            final double c1,
            final double d0,
            final double d1,
            final double t) {
        final double m1 = a0 + a1 * t;
        final double m2 = b0 + b1 * t;
        if (isZero(m1) && isZero(m2)) {
            if (isZero(c0 + c1 * t) && isZero(d0 + d1 * t)) {
                return Double.NaN;
            }
            return Double.POSITIVE_INFINITY;
        }
        return Math.abs(m1) > Math.abs(m2) ? (-c0 - c1 * t) / m1 : (-d0 - d1 * t) / m2;
    }

    private static boolean isZero(final double value) {
        return !(value < -EPSILON) && !(-value < -EPSILON);
    }

    private static boolean inRange(final double value, final double lo, final double hi) {
        return value >= lo && value <= hi;
    }

    private static int calcDivFromFlatness(
            final float flatness, final int offsetPow, final int minDiv, final int maxDiv) {
        final int exp = (int) ((float) Math.log(flatness * TEN_F) + offsetPow);
        int div = (int) (float) Math.pow(TWO_F, exp);
        if (div < minDiv) div = minDiv;
        else if (div > maxDiv) div = maxDiv;
        return div;
    }

    private static float evalCubic(final float p0, final float p1, final float p2, final float p3, final float t) {
        final float t2 = t * t;
        final float t3 = t2 * t;
        final float omt = ONE_F - t;
        final float omt2 = omt * omt;
        final float omt3 = omt2 * omt;
        return omt3 * p0 + THREE_F * omt2 * t * p1 + THREE_F * omt * t2 * p2 + t3 * p3;
    }

    private static float maxCurveDeviationSq(
            final float ax,
            final float ay,
            final float c1x,
            final float c1y,
            final float c2x,
            final float c2y,
            final float bx,
            final float by) {
        final float d1 = pointSegmentDistSq(ax, ay, bx, by, c1x, c1y);
        final float d2 = pointSegmentDistSq(ax, ay, bx, by, c2x, c2y);
        return Math.max(d1, d2);
    }

    private static float pointSegmentDistSq(
            final float ax, final float ay, final float bx, final float by, final float px, final float py) {
        final float abx = bx - ax;
        final float aby = by - ay;
        float apx = px - ax;
        float apy = py - ay;
        float dot = apx * abx + apy * aby;
        final float projLenSq;
        if (dot <= ZERO_F) {
            projLenSq = ZERO_F;
        } else {
            apx = abx - apx;
            apy = aby - apy;
            dot = apx * abx + apy * aby;
            projLenSq = dot <= ZERO_F ? ZERO_F : (dot * dot) / (abx * abx + aby * aby);
        }
        final float rem = apx * apx + apy * apy - projLenSq;
        return Math.max(rem, ZERO_F);
    }

    private static float distSq(final float ax, final float ay, final float bx, final float by) {
        final float dx = ax - bx;
        final float dy = ay - by;
        return dx * dx + dy * dy;
    }

    private static float dist(final float ax, final float ay, final float bx, final float by) {
        return (float) Math.sqrt(distSq(ax, ay, bx, by));
    }
}
