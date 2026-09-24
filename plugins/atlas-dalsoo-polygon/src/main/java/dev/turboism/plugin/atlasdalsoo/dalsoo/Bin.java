/*
 * Ported from whitegreen/Dalsoo-Bin-Packing (https://github.com/whitegreen/Dalsoo-Bin-Packing),
 * MIT License, Copyright (c) 2018 Hao Hua. See LICENSE and NOTICE in this module.
 * Algorithms adapted from Abeysooriya 2018 and Dalalah 2014 by the upstream project.
 *
 * Turboism extensions: per-poly rotation candidate lists (issued/quarter/free),
 * pre-placed fixed-position obstacles, progress callbacks and cancellation.
 * Overlap tests compare buffered outlines on both sides (margin/2 each) instead of
 * upstream's buffered-candidate vs raw-placed test, which keeps the same
 * minimum-distance guarantee symmetrically.
 */
package dev.turboism.plugin.atlasdalsoo.dalsoo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;

/** Single rectangular bin; packs pending polygons around already placed ones. */
final class Bin {

    private static final double AREA_SC = 1E-6;
    private static final int GRID_DIV = 32;

    private final List<PackedPoly> packedPolys = new ArrayList<>();
    private final List<PackedPoly> pendingPolys = new ArrayList<>();
    private final List<double[][]> perPolyTrigos = new ArrayList<>();
    private final Map<Integer, List<PackedPoly>> grid = new HashMap<>();
    private int queryStamp;
    private Convex cntConvex;

    private final double binWidth;
    private final double binHeight;
    private final double preferX;
    private final double cellWidth;
    private final double cellHeight;
    private final int gridW;
    private final int gridH;
    private final BooleanSupplier cancelled;
    private final IntConsumer progress;

    /**
     * @param polys        source polygons (locked items keep their issued transform)
     * @param obstacles    already-placed polygons in bin space (never moved)
     * @param trigos       per-poly rotation candidates, same order as {@code polys}
     * @param width        bin width
     * @param height       bin height
     * @param hSkew        horizontal packing preference {@code [0..1]}
     * @param cancelled    cooperative cancellation probe
     * @param progress     called with the packed-item count after each placement
     */
    Bin(final List<SourcePoly> polys, final List<PackedPoly> obstacles,
        final List<double[][]> trigos, final double width, final double height,
        final double hSkew, final Double segmentMaxLength,
        final BooleanSupplier cancelled, final IntConsumer progress) {
        if (hSkew < 0 || hSkew > 1) {
            throw new IllegalArgumentException("hSkew must be in [0,1]");
        }
        for (int i = 0; i < polys.size(); i++) {
            final SourcePoly sp = polys.get(i);
            pendingPolys.add(new PackedPoly(sp.id, sp.inpts, sp.outpts, segmentMaxLength));
            perPolyTrigos.add(trigos.get(i));
        }
        this.preferX = hSkew;
        this.binWidth = width;
        this.binHeight = height;
        this.cellWidth = Math.max(1.0, width / GRID_DIV);
        this.cellHeight = Math.max(1.0, height / GRID_DIV);
        this.gridW = Math.max(1, (int) Math.ceil(width / cellWidth));
        this.gridH = Math.max(1, (int) Math.ceil(height / cellHeight));
        this.cancelled = cancelled == null ? () -> false : cancelled;
        this.progress = progress == null ? i -> { } : progress;
        if (obstacles != null) {
            for (final PackedPoly obstacle : obstacles) {
                packedPolys.add(obstacle);
                gridInsert(obstacle);
            }
        }
    }

    /** Packs as many pending polygons as fit; unplaced ones stay in {@link #unplacedIds()}. */
    void pack(final boolean abey) {
        checkCancelled();
        Collections.sort(pendingPolys); // ascending by area; iteration goes from the end
        if (!packedPolys.isEmpty()) {
            cntConvex = containerHullOf(packedPolys);
        }
        if (!pendingPolys.isEmpty() && packedPolys.isEmpty()) {
            // origin placement is only valid for an empty bin; with pre-placed
            // obstacles every poly must go through the feasibility-checked path
            packFirstPoly();
        }
        final int size = pendingPolys.size();
        for (int i = 0; i < size; i++) {
            checkCancelled();
            final boolean placed = abey
                ? packPolyAbey(size - 1 - i)
                : packPolyDalalah(size - 1 - i);
            if (placed) {
                progress.accept(packedPolys.size());
            }
        }
        pendingPolys.removeIf(Objects::isNull);
    }

    private void checkCancelled() {
        if (cancelled.getAsBoolean()) {
            throw new PackingCancelledException();
        }
    }


    private Convex containerHullOf(final List<PackedPoly> placed) {
        final Convex hull = new Convex(placed.get(0));
        for (int i = 1; i < placed.size(); i++) {
            for (final double[] p : placed.get(i).inpts) {
                hull.incrementHull(p);
            }
        }
        return hull;
    }

    private void packFirstPoly() {
        int rotid = -1;
        double minArea = Double.MAX_VALUE;
        final int sid = pendingPolys.size() - 1; // largest first
        final PackedPoly first = pendingPolys.get(sid);
        final double[][] trigos = perPolyTrigos.get(sid);
        for (int i = 0; i < trigos.length; i++) {
            final double[][] tp = Geom.rotate(first.outpts, trigos[i]);
            final double[] bd = Geom.boundingBox(tp);
            if (bd[2] - bd[0] > binWidth || bd[3] - bd[1] > binHeight) {
                continue;
            }
            double area = AREA_SC * (bd[2] - bd[0]) * (bd[3] - bd[1]);
            final double[] center = Geom.centroid(tp);
            final double len = preferX * (center[0] - bd[0]) + (1 - preferX) * (center[1] - bd[1]);
            area *= len;
            if (minArea > area) {
                minArea = area;
                rotid = i;
            }
        }
        if (rotid < 0) {
            // no rotation fits the empty bin: keep it pending so it surfaces as
            // unplaced instead of a silently out-of-bounds placement
            return;
        }
        final double[][] tp = Geom.rotate(first.outpts, trigos[rotid]);
        final double[] bd = Geom.boundingBox(tp);
        first.fixRotateMove(trigos[rotid], new double[] {-bd[0], -bd[1]});
        pendingPolys.remove(sid);
        perPolyTrigos.remove(sid);
        placePackedPoly(first);
        if (cntConvex == null) {
            cntConvex = new Convex(first);
        } else {
            for (final double[] p : first.inpts) {
                cntConvex.incrementHull(p);
            }
        }
        progress.accept(packedPolys.size());
    }

    /** Abeysooriya 2018: continuous rotation via edge alignment. */
    private boolean packPolyAbey(final int sid) {
        final PackedPoly poly = pendingPolys.get(sid);
        final double[][] opl = poly.outpts;
        if (perPolyTrigos.get(sid).length == 1) {
            // issued-angle lock: continuous search reduces to the dalalah path
            return packPolyDalalah(sid);
        }
        double minArea = Double.MAX_VALUE;
        double[] minCossin = null;
        double[] minTrans = null;
        Convex minCon = null;
        for (int i = 0; i < opl.length; i++) { // each vertex of new poly
            final double[] p = opl[i];
            final double[] d0 = Geom.sub(opl[(i - 1 + opl.length) % opl.length], p);
            final double[] d2 = Geom.sub(opl[(i + 1) % opl.length], p);
            final double mag0 = Math.sqrt(Geom.dot(d0, d0));
            final double mag2 = Math.sqrt(Geom.dot(d2, d2));
            if (mag0 < Geom.PRECISION || mag2 < Geom.PRECISION) {
                continue;
            }
            final double cb0 = d0[0] / mag0;
            final double sb0 = d0[1] / mag0;
            final double cb2 = d2[0] / mag2;
            final double sb2 = d2[1] / mag2;

            for (final PackedPoly fixed : packedPolys) {
                final double[][] fopl = fixed.outpts;
                for (int j = 0; j < fopl.length; j++) { // each vertex of each fixed poly
                    final double[] v = fopl[j];
                    final double[] t0 = Geom.sub(fopl[(j - 1 + fopl.length) % fopl.length], v);
                    final double[] t2 = Geom.sub(fopl[(j + 1) % fopl.length], v);
                    final double m0 = Math.sqrt(Geom.dot(t0, t0));
                    final double m2 = Math.sqrt(Geom.dot(t2, t2));
                    if (m0 < Geom.PRECISION || m2 < Geom.PRECISION) {
                        continue;
                    }
                    final double ca0 = t0[0] / m0;
                    final double sa0 = t0[1] / m0;
                    final double ca2 = t2[0] / m2;
                    final double sa2 = t2[1] / m2;

                    for (int h = 0; h < 2; h++) { // two alignment angles
                        final double[] cossin;
                        if (h == 0) {
                            cossin = new double[] {
                                ca0 * cb2 + sa0 * sb2,
                                sa0 * cb2 - ca0 * sb2
                            }; // a0 - b2
                        } else {
                            cossin = new double[] {
                                ca2 * cb0 + sa2 * sb0,
                                sa2 * cb0 - ca2 * sb0
                            }; // a2 - b0
                        }
                        final double[][] rotOpl = Geom.rotate(opl, cossin);
                        final double[] trans = Geom.sub(v, rotOpl[i]);
                        final double[] rotBb = Geom.boundingBox(rotOpl);
                        if (!insideBin(rotBb, trans[0], trans[1])) {
                            continue;
                        }
                        final double[][] transRotOutpoly = translate(trans, rotOpl);
                        if (isFeasible(transRotOutpoly)) {
                            final double[][] transRotInpoly =
                                translate(trans, Geom.rotate(poly.inpts, cossin));
                            final Convex tmpcon = cntConvex.clone();
                            for (final double[] trp : transRotInpoly) {
                                tmpcon.incrementHull(trp);
                            }
                            final double conarea = AREA_SC * Geom.area(tmpcon.vertices());
                            final double[] center = Geom.centroid(transRotInpoly);
                            final double area = conarea
                                * (preferX * Math.abs(center[0]) + (1 - preferX) * Math.abs(center[1]));
                            if (minArea > area) {
                                minArea = area;
                                minCossin = cossin;
                                minTrans = trans;
                                minCon = tmpcon;
                            }
                        }
                    }
                }
            }
        }
        if (minCossin == null) {
            return false;
        }
        poly.fixRotateMove(minCossin, minTrans);
        pendingPolys.set(sid, null);
        perPolyTrigos.set(sid, new double[0][]);
        placePackedPoly(poly);
        cntConvex = minCon;
        return true;
    }

    /** Dalalah 2014: discrete rotation candidates. */
    private boolean packPolyDalalah(final int sid) {
        final PackedPoly stp = pendingPolys.get(sid);
        final double[][] trigos = perPolyTrigos.get(sid);
        double minArea = Double.MAX_VALUE;
        int minRotid = -1;
        double[] minTrans = null;
        Convex minCon = null;
        for (int i = 0; i < trigos.length; i++) { // each candidate angle
            final double[][] rotatedOutpoly = Geom.rotate(stp.outpts, trigos[i]);
            final double[][] rotatedInpoly = Geom.rotate(stp.inpts, trigos[i]);
            final double[] rotatedBb = Geom.boundingBox(rotatedOutpoly);
            for (final double[] p : rotatedOutpoly) { // each vertex of new poly
                for (final PackedPoly fixed : packedPolys) {
                    for (final double[] v : fixed.outpts) { // each vertex of each fixed poly
                        final double tx = v[0] - p[0];
                        final double ty = v[1] - p[1];
                        if (!insideBin(rotatedBb, tx, ty)) {
                            continue;
                        }
                        final double[] trans = {tx, ty};
                        final double[][] transRotOutpoly = translate(trans, rotatedOutpoly);
                        final double[] transBb = {rotatedBb[0] + tx, rotatedBb[1] + ty,
                            rotatedBb[2] + tx, rotatedBb[3] + ty};
                        if (isFeasible(transRotOutpoly, transBb)) {
                            final PlacementScore sc = score(translate(trans, rotatedInpoly),
                                transRotOutpoly);
                            if (minArea > sc.area) {
                                minArea = sc.area;
                                minRotid = i;
                                minTrans = trans;
                                minCon = sc.convex;
                            }
                        }
                    }
                }
            }
        }
        if (minRotid < 0) {
            // vertex-contact placement can fail even on a nearly empty page
            // (e.g. one large poly vs a dense blob): fall back to a deterministic
            // corner lattice derived from placed bounding boxes and bin edges
            for (int i = 0; i < trigos.length; i++) {
                final double[][] rotatedOutpoly = Geom.rotate(stp.outpts, trigos[i]);
                final double[][] rotatedInpoly = Geom.rotate(stp.inpts, trigos[i]);
                final double[] bb = Geom.boundingBox(rotatedOutpoly);
                for (final double[] corner : cornerLattice()) {
                    final double tx = corner[0] - bb[0];
                    final double ty = corner[1] - bb[1];
                    if (!insideBin(bb, tx, ty)) {
                        continue;
                    }
                    final double[] trans = {tx, ty};
                    final double[][] transRotOutpoly = translate(trans, rotatedOutpoly);
                    if (isFeasible(transRotOutpoly)) {
                        final PlacementScore sc = score(translate(trans, rotatedInpoly),
                            transRotOutpoly);
                        if (minArea > sc.area) {
                            minArea = sc.area;
                            minRotid = i;
                            minTrans = trans;
                            minCon = sc.convex;
                        }
                    }
                }
            }
        }
        if (minRotid < 0) {
            return false;
        }
        stp.fixRotateMove(trigos[minRotid], minTrans);
        pendingPolys.set(sid, null);
        perPolyTrigos.set(sid, new double[0][]);
        placePackedPoly(stp);
        cntConvex = minCon;
        return true;
    }

    /**
     * Candidate top-left anchor points: bin corners plus every placed poly's
     * bounding-box right/top edges crossed with every left/bottom edge — the
     * classic no-fit corner set, deterministic in placement order.
     */
    private List<double[]> cornerLattice() {
        final List<double[]> xs = new ArrayList<>();
        final List<double[]> ys = new ArrayList<>();
        xs.add(new double[] {0});
        ys.add(new double[] {0});
        for (final PackedPoly fixed : packedPolys) {
            xs.add(new double[] {fixed.outBb[0]});
            xs.add(new double[] {fixed.outBb[2]});
            ys.add(new double[] {fixed.outBb[1]});
            ys.add(new double[] {fixed.outBb[3]});
        }
        final List<double[]> corners = new ArrayList<>(xs.size() * ys.size());
        for (final double[] y : ys) {
            for (final double[] x : xs) {
                corners.add(new double[] {x[0], y[0]});
            }
        }
        return corners;
    }

    private record PlacementScore(double area, Convex convex) {
    }

    /** Scores a feasible placement: container-hull growth times distance preference. */
    private PlacementScore score(final double[][] transRotInpoly,
        final double[][] transRotOutpoly) {
        final Convex tmpcon = cntConvex == null
            ? new Convex(transRotInpoly)
            : cntConvex.clone();
        if (cntConvex != null) {
            for (final double[] trp : transRotInpoly) {
                tmpcon.incrementHull(trp);
            }
        }
        final double conarea = AREA_SC * Geom.area(tmpcon.vertices());
        final double[] center = Geom.centroid(transRotOutpoly);
        final double area = conarea
            * (preferX * Math.abs(center[0]) + (1 - preferX) * Math.abs(center[1]));
        return new PlacementScore(area, tmpcon);
    }

    /**
     * Feasibility: inside the bin and no strict overlap with placed buffered
     * outlines. Strict semantics intentionally admit exact vertex/edge contact,
     * which is how tightly packed placements are found. The overlap scan only
     * visits placed polys whose grid cells intersect the candidate bounding box;
     * skipped pairs would be rejected by {@link Geom#overlapStrict}'s own
     * bounding-box test, so the result is identical to an exhaustive scan.
     */
    private boolean isFeasible(final double[][] poly) {
        return isFeasible(poly, Geom.boundingBox(poly));
    }

    private boolean isFeasible(final double[][] poly, final double[] bb) {
        if (bb[0] < -Geom.PRECISION || bb[2] > binWidth + Geom.PRECISION
            || bb[1] < -Geom.PRECISION || bb[3] > binHeight + Geom.PRECISION) {
            return false;
        }
        final double[] center = Geom.centroid(poly);
        final int stamp = ++queryStamp;
        final int x0 = cellX(bb[0]);
        final int x1 = cellX(bb[2]);
        final int y0 = cellY(bb[1]);
        final int y1 = cellY(bb[3]);
        for (int cy = y0; cy <= y1; cy++) {
            for (int cx = x0; cx <= x1; cx++) {
                final List<PackedPoly> cell = grid.get(cy * gridW + cx);
                if (cell == null) {
                    continue;
                }
                for (final PackedPoly fixed : cell) {
                    if (fixed.lastQuery == stamp) {
                        continue;
                    }
                    fixed.lastQuery = stamp;
                    if (Geom.overlapStrict(poly, bb, center, fixed.outpts, fixed.outBb,
                        fixed.outCentroid)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Whether the ring at {@code bb} translated by {@code (tx,ty)} stays in the bin. */
    private boolean insideBin(final double[] bb, final double tx, final double ty) {
        return bb[0] + tx >= -Geom.PRECISION && bb[2] + tx <= binWidth + Geom.PRECISION
            && bb[1] + ty >= -Geom.PRECISION && bb[3] + ty <= binHeight + Geom.PRECISION;
    }

    private int cellX(final double x) {
        final int c = (int) (x / cellWidth);
        return c < 0 ? 0 : Math.min(c, gridW - 1);
    }

    private int cellY(final double y) {
        final int c = (int) (y / cellHeight);
        return c < 0 ? 0 : Math.min(c, gridH - 1);
    }

    private void gridInsert(final PackedPoly poly) {
        final int x0 = cellX(poly.outBb[0]);
        final int x1 = cellX(poly.outBb[2]);
        final int y0 = cellY(poly.outBb[1]);
        final int y1 = cellY(poly.outBb[3]);
        for (int cy = y0; cy <= y1; cy++) {
            for (int cx = x0; cx <= x1; cx++) {
                grid.computeIfAbsent(cy * gridW + cx, k -> new ArrayList<>()).add(poly);
            }
        }
    }

    private static double[][] translate(final double[] v, final double[][] ps) {
        final double[][] out = new double[ps.length][];
        for (int i = 0; i < ps.length; i++) {
            out[i] = new double[] {ps[i][0] + v[0], ps[i][1] + v[1]};
        }
        return out;
    }

    private void placePackedPoly(final PackedPoly poly) {
        poly.place();
        packedPolys.add(poly);
        gridInsert(poly);
    }

    List<PackedPoly> packedPolys() {
        return packedPolys;
    }

    List<Integer> unplacedIds() {
        final List<Integer> ids = new ArrayList<>();
        for (final PackedPoly p : pendingPolys) {
            if (p != null) {
                ids.add(p.id);
            }
        }
        return ids;
    }

}
