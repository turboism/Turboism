package dev.turboism.plugin.turbomesh.domain;

import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;

/**
 * Curvature-driven resampling of a closed boundary ring in UV space.
 *
 * <p>Three passes, deliberately biased toward emitting <em>fewer</em> points on straight
 * geometry:</p>
 *
 * <ol>
 *   <li>Emit every original vertex whose turn angle exceeds {@code collinearTurnRadians} —
 *       real corners are structural and survive verbatim. Near-collinear vertices (dense
 *       auto-meshed curves, raster noise) are skipped provisionally.</li>
 *   <li>Cap the arc length between consecutive samples at {@code maxBoundarySpacingUv} so a
 *       long straight edge still gains a modest number of deformation-support points —
 *       subdivided at original vertices or straight midpoints, never more than needed.</li>
 *   <li>Enforce a chord-error bound ({@code sagittaUv}) that re-inserts skipped original
 *       vertices wherever a chord drifts off the polyline. Curved stretches earn back exactly
 *       as many points as their curvature demands; straight stretches earn none.</li>
 * </ol>
 *
 * <p>Vertices sharper than {@code tipTurnRadians} additionally emit an interior anchor on the
 * inward angle bisector, forming the protection ring around tips.
 */
public final class BoundarySampler {

    /** One resampled ring: boundary vertices plus interior anchor points near tips. */
    public record SampledRing(List<Point2> ring, List<Point2> interiorAnchors) {}

    /** Coarsened ring plus the original ring index of every kept vertex. */
    public record Simplified(List<Point2> points, int[] originalIndices) {}

    private BoundarySampler() {}

    /**
     * First-stage boundary coarsening: Douglas–Peucker over the closed ring between consecutive
     * locked vertices (detected corners), so noisy auto-meshed outlines collapse to their coarse
     * shape before the curvature passes refine them. Locked vertices always survive.
     *
     * @param epsilon maximum allowed deviation of a chord from the dropped vertices, UV units
     * @param locked original ring indices that must be kept (may be empty)
     */

    /**
     * Laplacian ring smoothing: smooths polyline curves while locking sharp corner vertices.
     * Eliminates rasterized staircase jaggies (Cubism PolygonMaker2 _boundsSmooting parity).
     */
    public static List<Point2> smoothRing(
            final List<Point2> ring, final java.util.BitSet locked, final int iterations, final double alpha) {
        final int n = ring.size();
        if (n < 4 || iterations <= 0) {
            return ring;
        }
        List<Point2> current = new ArrayList<>(ring);
        for (int iter = 0; iter < iterations; iter++) {
            final List<Point2> next = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                if (locked != null && locked.get(i)) {
                    next.add(current.get(i));
                    continue;
                }
                final Point2 prev = current.get((i - 1 + n) % n);
                final Point2 cur = current.get(i);
                final Point2 nxt = current.get((i + 1) % n);
                final double avgX = (prev.x() + nxt.x()) * 0.5;
                final double avgY = (prev.y() + nxt.y()) * 0.5;
                final double sx = cur.x() + alpha * (avgX - cur.x());
                final double sy = cur.y() + alpha * (avgY - cur.y());
                next.add(new Point2((float) sx, (float) sy));
            }
            current = next;
        }
        return current;
    }

    /**
     * Coarsens a closed boundary ring using Douglas–Peucker while locking specified vertices.
     *
     * @param ring boundary points
     * @param epsilon maximum deviation
     * @param locked vertex indices to preserve
     * @return coarsened ring
     */
    public static Simplified simplify(final List<Point2> ring, final double epsilon, final java.util.BitSet locked) {
        final int n = ring.size();
        if (n < 5 || epsilon <= 0.0) {
            final int[] identity = new int[n];
            for (int i = 0; i < n; i++) {
                identity[i] = i;
            }
            return new Simplified(new ArrayList<>(ring), identity);
        }
        // Anchors: locked vertices; with none, the two farthest-apart indices.
        final List<Integer> anchors = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (locked.get(i)) {
                anchors.add(i);
            }
        }
        if (anchors.size() == 1) {
            anchors.add((anchors.get(0) + n / 2) % n);
        } else if (anchors.isEmpty()) {
            anchors.add(0);
            anchors.add(n / 2);
        }
        java.util.Collections.sort(anchors);
        final List<Point2> out = new ArrayList<>();
        final List<Integer> outIdx = new ArrayList<>();
        for (int a = 0; a < anchors.size(); a++) {
            final int from = anchors.get(a);
            final int to = anchors.get((a + 1) % anchors.size());
            out.add(ring.get(from));
            outIdx.add(from);
            // Walk the open polyline from -> to (wrapping), DP-keeping vertices in between.
            final List<Integer> kept = douglasPeucker(ring, from, to, epsilon);
            for (final int v : kept) {
                out.add(ring.get(v));
                outIdx.add(v);
            }
        }
        final java.util.BitSet lockedInCoarse = new java.util.BitSet();
        for (int i = 0; i < outIdx.size(); i++) {
            if (locked.get(outIdx.get(i))) {
                lockedInCoarse.set(i);
            }
        }
        final List<Point2> smoothed = smoothRing(out, lockedInCoarse, 2, 0.35);
        return new Simplified(
                smoothed, outIdx.stream().mapToInt(Integer::intValue).toArray());
    }

    /**
     * Keeps vertices of the open polyline {@code from..to} (exclusive of both ends, wrapping
     * forward) whose chord deviation exceeds {@code epsilon}; the farthest vertex splits
     * recursively. Returned indices are in walk order.
     */
    private static List<Integer> douglasPeucker(
            final List<Point2> ring, final int from, final int to, final double epsilon) {
        final int n = ring.size();
        final List<Integer> kept = new ArrayList<>();
        final int steps = ((to - from) % n + n) % n;
        if (steps < 2) {
            return kept;
        }
        final int[] stackFrom = new int[steps + 1];
        final int[] stackTo = new int[steps + 1];
        final boolean[] keep = new boolean[n];
        int top = 0;
        stackFrom[0] = from;
        stackTo[0] = to;
        while (top >= 0) {
            final int a = stackFrom[top];
            final int b = stackTo[top];
            top--;
            final int len = ((b - a) % n + n) % n;
            if (len < 2) {
                continue;
            }
            final Point2 pa = ring.get(a);
            final Point2 pb = ring.get(b);
            double worst = -1.0;
            int worstV = -1;
            for (int s = 1; s < len; s++) {
                final int v = (a + s) % n;
                final double d = distanceToSegment(ring.get(v), pa, pb);
                if (d > worst) {
                    worst = d;
                    worstV = v;
                }
            }
            if (worst > epsilon && worstV >= 0) {
                keep[worstV] = true;
                top++;
                stackFrom[top] = a;
                stackTo[top] = worstV;
                top++;
                stackFrom[top] = worstV;
                stackTo[top] = b;
            }
        }
        for (int s = 1; s < steps; s++) {
            final int v = (from + s) % n;
            if (keep[v]) {
                kept.add(v);
            }
        }
        return kept;
    }

    /**
     * Resamples {@code ring} (closed, not repeating the first vertex) in UV space.
     *
     * @param maxSpacingUv maximum arc length allowed between consecutive emitted samples;
     *     deformation support only — curvature needs are handled by the sagitta bound
     * @param mask occupancy used to keep interior anchors inside the shape; may be {@code null}
     */
    public static SampledRing resample(
            final List<Point2> ring,
            final double maxSpacingUv,
            final PlacementSettings settings,
            final DomainMask mask) {
        return resample(ring, maxSpacingUv, settings, mask, null);
    }

    /**
     * Feature-aware variant: vertices flagged in {@code protectedVertices} (by ring index) are
     * structural anchors regardless of their turn angle — detected corners survive verbatim.
     *
     * @param protectedVertices ring indices to force-keep; may be {@code null}
     */
    public static SampledRing resample(
            final List<Point2> ring,
            final double maxSpacingUv,
            final PlacementSettings settings,
            final DomainMask mask,
            final java.util.BitSet protectedVertices) {
        if (ring.size() < 3) {
            return new SampledRing(new ArrayList<>(ring), List.of());
        }
        final int n = ring.size();
        final double[] turn = new double[n];
        for (int i = 0; i < n; i++) {
            turn[i] = turnAngle(ring.get((i + n - 1) % n), ring.get(i), ring.get((i + 1) % n));
        }
        final boolean[] anchorAt = new boolean[n];
        for (int i = 0; i < n; i++) {
            anchorAt[i] =
                    turn[i] >= settings.tipTurnRadians() || (protectedVertices != null && protectedVertices.get(i));
        }

        // Pass 1 — structural vertices only. A sample is recorded as (point, segOf, tOf): vertex
        // `v` is the start of segment `v` (t=0); segment `i` runs ring[i] -> ring[i+1].
        final List<Point2> out = new ArrayList<>();
        final List<Integer> segOf = new ArrayList<>();
        final List<Double> tOf = new ArrayList<>();
        final List<Point2> anchors = new ArrayList<>();
        final double ringArea = signedArea(ring);
        for (int i = 0; i < n; i++) {
            if (turn[i] > settings.collinearTurnRadians() || anchorAt[i]) {
                out.add(ring.get(i));
                segOf.add(i);
                tOf.add(0.0);
            }
            if (anchorAt[i]) {
                emitAnchor(anchors, ring, ringArea, i, settings, mask);
            }
        }
        if (out.size() < 3) {
            // Near-perfect polyline (e.g. a very gentle dense curve): seed with the three most
            // turned vertices so the cap/sagitta passes have a ring to refine.
            out.clear();
            segOf.clear();
            tOf.clear();
            final int[] order = argsortDescending(turn);
            final int[] seeds = {order[0], order[1], order[2]};
            java.util.Arrays.sort(seeds);
            for (final int v : seeds) {
                out.add(ring.get(v));
                segOf.add(v);
                tOf.add(0.0);
            }
        }

        // Pass 2 — chord-length cap. Between consecutive samples (in ring order) the polyline arc
        // must not exceed maxSpacingUv; overshoots split at the arc midpoint. Midpoints prefer an
        // original vertex; when the arc is a bare segment the straight midpoint is interpolated.
        enforceMaxSpacing(out, segOf, tOf, ring, maxSpacingUv);

        // Pass 3 — chord-error bound restores skipped vertices where the boundary curves.
        enforceSagitta(out, segOf, tOf, ring, settings.sagittaUv());
        return new SampledRing(out, anchors);
    }

    /**
     * Splits every polyline arc between consecutive samples that is longer than
     * {@code maxSpacing}. Each iteration inserts the point at half the arc length — the original
     * vertex nearest the midpoint when the arc spans vertices, otherwise the interpolated
     * segment midpoint — so the loop strictly halves the worst gap and terminates.
     */
    private static void enforceMaxSpacing(
            final List<Point2> sampled,
            final List<Integer> segOf,
            final List<Double> tOf,
            final List<Point2> original,
            final double maxSpacing) {
        final int n = original.size();
        int guard = n + 64;
        while (guard-- > 0) {
            int worstChord = -1;
            double worstArc = maxSpacing;
            for (int i = 0; i < sampled.size(); i++) {
                final int j = (i + 1) % sampled.size();
                final double arc = arcLength(original, sampled, segOf, tOf, i, j, n);
                if (arc > worstArc) {
                    worstArc = arc;
                    worstChord = i;
                }
            }
            if (worstChord < 0) {
                return;
            }
            insertAtArcMidpoint(sampled, segOf, tOf, original, worstChord, (worstChord + 1) % sampled.size(), n);
        }
    }

    /**
     * Polyline arc length from sample {@code i} to sample {@code j}, measured along the original
     * ring. Samples are located by {@code (segment, t)}; the walk covers the rest of segment e1,
     * all whole segments in between, and the head of segment e2.
     */
    private static double arcLength(
            final List<Point2> original,
            final List<Point2> sampled,
            final List<Integer> segOf,
            final List<Double> tOf,
            final int i,
            final int j,
            final int n) {
        final int e1 = segOf.get(i);
        final int e2 = segOf.get(j);
        final double t1 = tOf.get(i);
        final double t2 = tOf.get(j);
        if (e1 == e2 && t1 <= t2) {
            return segLength(original, e1, n) * (t2 - t1);
        }
        double arc = segLength(original, e1, n) * (1.0 - t1) + segLength(original, e2, n) * t2;
        for (int s = (e1 + 1) % n; s != e2; s = (s + 1) % n) {
            arc += segLength(original, s, n);
        }
        return arc;
    }

    private static double segLength(final List<Point2> ring, final int segment, final int n) {
        final Point2 a = ring.get(segment);
        final Point2 b = ring.get((segment + 1) % n);
        return Math.hypot(b.x() - a.x(), b.y() - a.y());
    }

    /**
     * Inserts the arc midpoint between samples {@code i} and {@code j}: the original vertex
     * nearest the half-arc when the arc covers vertices, otherwise a point interpolated halfway
     * along the single shared segment.
     */
    private static void insertAtArcMidpoint(
            final List<Point2> sampled,
            final List<Integer> segOf,
            final List<Double> tOf,
            final List<Point2> original,
            final int i,
            final int j,
            final int n) {
        final int e1 = segOf.get(i);
        final int e2 = segOf.get(j);
        final double t1 = tOf.get(i);
        final double t2 = tOf.get(j);
        final int firstVertex = t1 > 1.0 - 1.0e-9 ? (e1 + 2) % n : (e1 + 1) % n;
        final int stopVertex = t2 < 1.0e-9 ? e2 : (e2 + 1) % n;
        if (firstVertex == stopVertex) {
            // No interior original vertices on this arc: it is the tail of segment e1 plus the
            // head of segment e2 (or one shared segment). Interpolate at half the arc length.
            final double l1 = segLength(original, e1, n);
            double t;
            int seg;
            if (e1 == e2 && t1 <= t2) {
                seg = e1;
                t = (t1 + t2) * 0.5;
            } else {
                final double tailLen = l1 * (1.0 - t1);
                final double half = (tailLen + segLength(original, e2, n) * t2) * 0.5;
                if (half <= tailLen) {
                    seg = e1;
                    t = l1 < 1.0e-12 ? 1.0 : t1 + half / l1;
                } else {
                    seg = e2;
                    final double l2 = segLength(original, e2, n);
                    t = l2 < 1.0e-12 ? 0.0 : (half - tailLen) / l2;
                }
            }
            if (t > 1.0 - 1.0e-9) {
                // Lands on the vertex at the start of segment seg+1 — snap to it.
                final int v = (seg + 1) % n;
                sampled.add(i + 1, original.get(v));
                segOf.add(i + 1, v);
                tOf.add(i + 1, 0.0);
            } else if (t < 1.0e-9) {
                sampled.add(i + 1, original.get(seg));
                segOf.add(i + 1, seg);
                tOf.add(i + 1, 0.0);
            } else {
                final Point2 a = original.get(seg);
                final Point2 b = original.get((seg + 1) % n);
                sampled.add(i + 1, new Point2((float) (a.x() + (b.x() - a.x()) * t), (float)
                        (a.y() + (b.y() - a.y()) * t)));
                segOf.add(i + 1, seg);
                tOf.add(i + 1, t);
            }
            return;
        }
        // Walk vertices to the arc midpoint. `acc` is the arc position of vertex `v` once the
        // loop head is reached; the walk stops at the first interior vertex at or past half.
        final int lastInterior = (stopVertex - 1 + n) % n;
        final double half = arcLength(original, sampled, segOf, tOf, i, j, n) * 0.5;
        double acc = segLength(original, e1, n) * (1.0 - t1);
        for (int s = (e1 + 1) % n; s != firstVertex; s = (s + 1) % n) {
            acc += segLength(original, s, n);
        }
        int v = firstVertex;
        while (v != lastInterior && acc < half) {
            acc += segLength(original, v, n);
            v = (v + 1) % n;
        }
        sampled.add(i + 1, original.get(v));
        segOf.add(i + 1, v);
        tOf.add(i + 1, 0.0);
    }

    /**
     * Inserts the worst-fitting skipped original vertex wherever a chord deviates too far.
     * A chord between samples on segments {@code (e1, t1)} and {@code (e2, t2)} replaces exactly
     * the original vertices {@code e1+1 .. e2} (wrap-aware; endpoint {@code e2} only when
     * {@code t2} is interior).
     */
    private static void enforceSagitta(
            final List<Point2> sampled,
            final List<Integer> segOf,
            final List<Double> tOf,
            final List<Point2> original,
            final double sagitta) {
        if (sampled.size() < 3) {
            return;
        }
        final int n = original.size();
        int guard = n * 2 + 8;
        while (guard-- > 0) {
            int worstVertex = -1;
            int worstChord = -1;
            double worstDev = sagitta;
            for (int i = 0; i < sampled.size(); i++) {
                final int j = (i + 1) % sampled.size();
                final Point2 a = sampled.get(i);
                final Point2 b = sampled.get(j);
                final int e1 = segOf.get(i);
                final int e2 = segOf.get(j);
                // Vertices strictly inside the arc from sample i to sample j. The arc starts at
                // vertex e1+1 (or e1+2 when sample i IS vertex e1+1 via a vertex snap) and ends
                // before vertex e2 when sample j sits on it, or after it when t2 is interior.
                final int start = tOf.get(i) > 1.0 - 1.0e-9 ? (e1 + 2) % n : (e1 + 1) % n;
                final int stop = tOf.get(j) < 1.0e-9 ? e2 : (e2 + 1) % n;
                for (int v = start; v != stop; v = (v + 1) % n) {
                    final double dev = distanceToSegment(original.get(v), a, b);
                    if (dev > worstDev) {
                        worstDev = dev;
                        worstVertex = v;
                        worstChord = i;
                    }
                }
            }
            if (worstVertex < 0) {
                return;
            }
            // Insert the offending original vertex right after the chord start.
            sampled.add(worstChord + 1, original.get(worstVertex));
            segOf.add(worstChord + 1, worstVertex);
            tOf.add(worstChord + 1, 0.0);
        }
    }

    private static double distanceToSegment(final Point2 p, final Point2 a, final Point2 b) {
        final double abx = b.x() - a.x();
        final double aby = b.y() - a.y();
        final double len2 = abx * abx + aby * aby;
        if (len2 < 1.0e-18) {
            return Math.hypot(p.x() - a.x(), p.y() - a.y());
        }
        double t = ((p.x() - a.x()) * abx + (p.y() - a.y()) * aby) / len2;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(p.x() - (a.x() + t * abx), p.y() - (a.y() + t * aby));
    }

    /** Unsigned turn angle at {@code cur} in [0, π]. */
    static double turnAngle(final Point2 prev, final Point2 cur, final Point2 next) {
        final double ux = cur.x() - prev.x();
        final double uy = cur.y() - prev.y();
        final double vx = next.x() - cur.x();
        final double vy = next.y() - cur.y();
        final double lu = Math.hypot(ux, uy);
        final double lv = Math.hypot(vx, vy);
        if (lu < 1.0e-12 || lv < 1.0e-12) {
            return 0.0;
        }
        final double cos = (ux * vx + uy * vy) / (lu * lv);
        return Math.acos(Math.max(-1.0, Math.min(1.0, cos)));
    }

    /** Signed area of the closed ring (positive when wound counter-clockwise). */
    private static double signedArea(final List<Point2> ring) {
        final int n = ring.size();
        double area = 0.0;
        for (int i = 0; i < n; i++) {
            final Point2 a = ring.get(i);
            final Point2 b = ring.get((i + 1) % n);
            area += a.x() * b.y() - b.x() * a.y();
        }
        return area * 0.5;
    }

    /**
     * Pushes an interior anchor off a sharp vertex along the inward angle bisector. When a mask
     * is supplied the anchor is kept only while it stays inside the shape; the offset shrinks by
     * halves until it fits or falls below half the protection margin.
     */
    private static void emitAnchor(
            final List<Point2> anchors,
            final List<Point2> ring,
            final double ringArea,
            final int index,
            final PlacementSettings settings,
            final DomainMask mask) {
        final int n = ring.size();
        final Point2 prev = ring.get((index + n - 1) % n);
        final Point2 cur = ring.get(index);
        final Point2 next = ring.get((index + 1) % n);
        double ux = cur.x() - prev.x();
        double uy = cur.y() - prev.y();
        double vx = next.x() - cur.x();
        double vy = next.y() - cur.y();
        final double lu = Math.hypot(ux, uy);
        final double lv = Math.hypot(vx, vy);
        if (lu < 1.0e-12 || lv < 1.0e-12) {
            return;
        }
        ux /= lu;
        uy /= lu;
        vx /= lv;
        vy /= lv;
        // Bisector of the wedge between the rays cur->prev (-u) and cur->next (v): +b
        // cuts through the minor angle — the material side at convex corners; -b is the
        // reflex/hole direction. Which side holds material is resolved against the mask,
        // so hole rings and CW-wound sources need no special casing.
        double bx = vx - ux;
        double by = vy - uy;
        final double lb = Math.hypot(bx, by);
        if (lb < 1.0e-9) {
            return;
        }
        bx /= lb;
        by /= lb;
        double dir = 0.0;
        if (mask != null) {
            // A probe closer than one cell to the vertex reads the corner cell either way and
            // cannot tell the two sides apart; start at least a cell out, then double.
            double probe = Math.max(settings.interiorMarginUv(), mask.cellUv());
            for (int attempt = 0; attempt < 3 && dir == 0.0; attempt++) {
                final boolean plusSolid = mask.solidAtUv(cur.x() + bx * probe, cur.y() + by * probe);
                final boolean minusSolid = mask.solidAtUv(cur.x() - bx * probe, cur.y() - by * probe);
                if (plusSolid != minusSolid) {
                    dir = plusSolid ? 1.0 : -1.0;
                }
                probe *= 2.0;
            }
        }
        if (dir == 0.0) {
            // Mask absent or ambiguous: the wedge side is material when the local turn agrees
            // with the ring's winding (convex corner); opposite on reflex corners.
            dir = (ux * vy - uy * vx) * ringArea > 0.0 ? 1.0 : -1.0;
        }
        bx *= dir;
        by *= dir;
        double offset = settings.interiorMarginUv();
        for (int attempt = 0; attempt < 4; attempt++) {
            final double x = cur.x() + bx * offset;
            final double y = cur.y() + by * offset;
            final double midX = cur.x() + bx * offset * 0.5;
            final double midY = cur.y() + by * offset * 0.5;
            if (mask == null || (mask.solidAtUv(x, y) && mask.solidAtUv(midX, midY))) {
                anchors.add(new Point2((float) x, (float) y));
                return;
            }
            offset *= 0.5;
        }
    }

    private static int[] argsortDescending(final double[] values) {
        final Integer[] order = new Integer[values.length];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Double.compare(values[b], values[a]));
        final int[] out = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            out[i] = order[i];
        }
        return out;
    }
}
