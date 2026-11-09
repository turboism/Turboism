package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Samples a pixel border into mesh vertices at a curvature-adaptive density.
 *
 * <p>Staircase noise and drawn corners need opposite treatment: fairing removes the steps, yet it
 * also rounds a corner the artist intended. The sampler therefore locks every junction and every
 * sharp corner first, fairs only the spans between them, and resamples each span with a spacing
 * that shrinks as the curve tightens.</p>
 */
final class CurvatureBoundarySampler {

    /** Chord arms of the multi-scale corner detector, in pixels. */
    static final double[] CORNER_ARMS_PX = {3.5, 6.5, 10.0};

    /** Turning angle from which a vertex counts as a drawn corner, in degrees. */
    static final double CORNER_TURN_DEGREES = 42.0;

    /** Smallest distance between two locked corners, in pixels. */
    static final double CORNER_SEPARATION_PX = 3.0;

    /** Largest displacement of a faired vertex from its raw contour position, in pixels. */
    static final double FAIRING_CLAMP_PX = 0.80;

    /** Taubin shrink step, which removes the single-pixel staircase. */
    static final double TAUBIN_LAMBDA = 0.50;

    /** Sum of both Taubin passband parameters; it keeps the fairing volume-preserving. */
    static final double TAUBIN_PASSBAND = 0.10;

    /** Passes of the Taubin pair; more passes drift the curve further from the raw contour. */
    static final int TAUBIN_ITERATIONS = 12;

    /** Smallest curvature the arc-length integrator resolves, in inverse pixels. */
    static final double MIN_CURVATURE = 1.0e-6;

    /** Chord deviation of the resampled curve, as a normaliser of the curvature term. */
    static final double CHORD_DEVIATION_WEIGHT = 8.0;

    /** Densest curve spacing, as a fraction of the maximum spacing. */
    static final double CURVE_MIN_RATIO = 0.30;

    /** Lipschitz bound that smooths the target spacing along the arc length. */
    static final double SPACING_LIPSCHITZ = 0.35;

    /** Share of the local spacing that a resampled vertex keeps from a pinned span end. */
    static final double END_GAP_RATIO = 0.5;

    /** Distance below which two emitted points count as one, in pixels. */
    private static final double MERGE_EPS_PX = 1.0e-9;

    private static final double COSINE_FLOOR = 1.0e-12;

    private CurvatureBoundarySampler() {}

    /**
     * Fairs and resamples one border chain.
     *
     * @param raw pixel border chain, consecutive points one pixel apart
     * @param tolerance chord deviation budget in pixels; zero asks for the densest curve spacing
     * @param minSpacing lower bound of the emitted vertex distance in pixels
     * @param maxSpacing upper bound of the emitted vertex distance in pixels
     * @return sampled chain with every locked corner kept at its raw position
     */
    static Polyline sample(
            final Polyline raw, final double tolerance, final double minSpacing, final double maxSpacing) {
        if (raw.size() < 2) {
            return raw;
        }
        final int size = raw.size();
        double[] xs = new double[size];
        double[] ys = new double[size];
        for (int i = 0; i < size; i++) {
            xs[i] = raw.x(i);
            ys[i] = raw.y(i);
        }
        final boolean cyclic = raw.closed() && size > 2;
        final double[] scores = cornerScores(xs, ys, cyclic);
        final int seam = cyclic ? straightestIndex(scores) : 0;
        if (seam > 0) {
            xs = rotated(xs, seam);
            ys = rotated(ys, seam);
        }
        if (cyclic) {
            xs = appended(xs, xs[0]);
            ys = appended(ys, ys[0]);
        }
        final int walkSize = xs.length;
        final int end = walkSize - 1;
        final int[] locked = lockedIndices(scores, cyclic, seam, end);
        final List<double[]> points = new ArrayList<>(walkSize);
        int previous = 0;
        for (final int corner : locked) {
            if (corner <= previous) {
                continue;
            }
            final List<double[]> span = sampleSpan(xs, ys, previous, corner, tolerance, minSpacing, maxSpacing);
            for (int i = points.isEmpty() ? 0 : 1; i < span.size(); i++) {
                points.add(span.get(i));
            }
            previous = corner;
        }
        final int lastIndex = points.size() - 1;
        if (cyclic && closes(points.get(0), points.get(lastIndex))) {
            points.remove(lastIndex);
        }
        final double[] sampledX = new double[points.size()];
        final double[] sampledY = new double[points.size()];
        for (int i = 0; i < points.size(); i++) {
            sampledX[i] = points.get(i)[0];
            sampledY[i] = points.get(i)[1];
        }
        return new Polyline(sampledX, sampledY, raw.closed());
    }

    /**
     * Largest multi-scale chord turning angle at every vertex, in degrees.
     *
     * <p>Every arm follows the chain for a fixed arc length, so a diagonal step does not count as
     * two vertices and the angle stays comparable along the border.</p>
     */
    private static double[] cornerScores(final double[] xs, final double[] ys, final boolean cyclic) {
        final int size = xs.length;
        final double[] scores = new double[size];
        for (int i = 0; i < size; i++) {
            double best = 0.0;
            for (final double arm : CORNER_ARMS_PX) {
                final int back = walk(xs, ys, i, -arm, cyclic);
                final int forward = walk(xs, ys, i, arm, cyclic);
                if (back == i || forward == i) {
                    continue;
                }
                final double ax = xs[i] - xs[back];
                final double ay = ys[i] - ys[back];
                final double bx = xs[forward] - xs[i];
                final double by = ys[forward] - ys[i];
                final double denominator = Math.max(COSINE_FLOOR, Math.hypot(ax, ay) * Math.hypot(bx, by));
                final double cosine = Math.max(-1.0, Math.min(1.0, (ax * bx + ay * by) / denominator));
                best = Math.max(best, Math.toDegrees(Math.acos(cosine)));
            }
            scores[i] = best;
        }
        return scores;
    }

    /** Chain index reached after walking {@code length} pixels along the border from {@code from}. */
    private static int walk(
            final double[] xs, final double[] ys, final int from, final double length, final boolean cyclic) {
        final int size = xs.length;
        final int step = length < 0.0 ? -1 : 1;
        final double target = Math.abs(length);
        double travelled = 0.0;
        int current = from;
        while (travelled < target) {
            int candidate = current + step;
            if (candidate < 0 || candidate >= size) {
                if (!cyclic) {
                    break;
                }
                candidate = (candidate + size) % size;
            }
            travelled += Math.hypot(xs[candidate] - xs[current], ys[candidate] - ys[current]);
            current = candidate;
        }
        return current;
    }

    /** Vertex with the smallest turning angle, which therefore cuts no corner when the ring folds. */
    private static int straightestIndex(final double[] scores) {
        int best = 0;
        for (int i = 1; i < scores.length; i++) {
            if (scores[i] < scores[best]) {
                best = i;
            }
        }
        return best;
    }

    /**
     * Indices that the sampler may not move: both walk ends plus every non-maximum-suppressed
     * corner above the turn threshold.
     */
    private static int[] lockedIndices(final double[] scores, final boolean cyclic, final int seam, final int end) {
        final Integer[] candidates = new Integer[scores.length];
        int count = 0;
        for (int i = 0; i < scores.length; i++) {
            if (scores[i] < CORNER_TURN_DEGREES) {
                continue;
            }
            candidates[count++] = i;
        }
        final Integer[] ranked = Arrays.copyOf(candidates, count);
        Arrays.sort(
                ranked,
                Comparator.comparingDouble((Integer index) -> -scores[index]).thenComparingInt(index -> index));
        final boolean[] accepted = new boolean[scores.length];
        final List<Integer> locked = new ArrayList<>(count + 2);
        final int minimumGap = (int) Math.ceil(CORNER_SEPARATION_PX);
        for (final Integer index : ranked) {
            boolean clear = true;
            for (int offset = -minimumGap; offset <= minimumGap && clear; offset++) {
                final int probe = index + offset;
                if (offset == 0 || probe < 0 || probe >= scores.length) {
                    continue;
                }
                clear = !accepted[probe];
            }
            if (clear) {
                accepted[index] = true;
            }
        }
        final int shift = cyclic ? seam : 0;
        for (int i = 0; i < accepted.length; i++) {
            if (!accepted[i]) {
                continue;
            }
            final int mapped = cyclic ? (i - shift + scores.length) % scores.length : i;
            // The seam itself becomes the first walk index, so it needs no second entry.
            if (mapped > 0 && mapped < end) {
                locked.add(mapped);
            }
        }
        locked.sort(Integer::compare);
        final int[] out = new int[locked.size() + 2];
        out[0] = 0;
        for (int i = 0; i < locked.size(); i++) {
            out[i + 1] = locked.get(i);
        }
        out[out.length - 1] = end;
        return out;
    }

    /**
     * Fairs and resamples one span between two locked vertices.
     *
     * <p>The span ends stay where the raw chain put them, so a junction or corner keeps its exact
     * position and two neighbouring chains keep meeting at the same site. Equal steps of the
     * integrated density place the vertices, and any step that would land on top of a pinned end is
     * dropped so the mesh never gets a two-pixel edge at a corner.</p>
     */
    private static List<double[]> sampleSpan(
            final double[] xs,
            final double[] ys,
            final int from,
            final int to,
            final double tolerance,
            final double minSpacing,
            final double maxSpacing) {
        final int count = to - from + 1;
        final double[] fairedX = Arrays.copyOfRange(xs, from, to + 1);
        final double[] fairedY = Arrays.copyOfRange(ys, from, to + 1);
        final double[] rawX = fairedX.clone();
        final double[] rawY = fairedY.clone();
        fair(fairedX, fairedY, rawX, rawY);
        final List<double[]> points = new ArrayList<>(count);
        // The pinned ends reuse the raw coordinates, so neighbouring spans meet bit-exactly.
        points.add(new double[] {xs[from], ys[from]});
        final double[] arc = arcLengths(fairedX, fairedY);
        final double total = arc[count - 1];
        if (count < 2 || total <= MERGE_EPS_PX) {
            points.add(new double[] {xs[to], ys[to]});
            return points;
        }
        final double[] spacing = targetSpacings(fairedX, fairedY, arc, tolerance, minSpacing, maxSpacing);
        final double[] density = densityIntegral(spacing, arc);
        final double integral = density[count - 1];
        final int steps = Math.max(1, (int) Math.round(integral));
        final List<double[]> inner = new ArrayList<>(steps);
        int cursor = 0;
        for (int step = 1; step < steps; step++) {
            final double target = integral * step / steps;
            while (cursor < count - 2 && density[cursor + 1] < target) {
                cursor++;
            }
            final double densitySpan = density[cursor + 1] - density[cursor];
            final double fraction = densitySpan <= MERGE_EPS_PX ? 0.0 : (target - density[cursor]) / densitySpan;
            inner.add(new double[] {
                fairedX[cursor] + fraction * (fairedX[cursor + 1] - fairedX[cursor]),
                fairedY[cursor] + fraction * (fairedY[cursor + 1] - fairedY[cursor]),
                spacing[cursor] + fraction * (spacing[cursor + 1] - spacing[cursor])
            });
        }
        points.addAll(keepSpaced(inner, points.get(0), xs[to], ys[to], spacing[count - 1]));
        points.add(new double[] {xs[to], ys[to]});
        return points;
    }

    /** True when the two points describe the same position. */
    private static boolean closes(final double[] first, final double[] second) {
        return Math.abs(first[0] - second[0]) <= MERGE_EPS_PX && Math.abs(first[1] - second[1]) <= MERGE_EPS_PX;
    }

    /** Drops the resampled steps that would crowd a pinned span end. */
    private static List<double[]> keepSpaced(
            final List<double[]> inner,
            final double[] start,
            final double endX,
            final double endY,
            final double endSpacing) {
        final List<double[]> kept = new ArrayList<>(inner.size());
        double anchorX = start[0];
        double anchorY = start[1];
        for (final double[] point : inner) {
            final double gap = Math.hypot(point[0] - anchorX, point[1] - anchorY);
            if (gap >= END_GAP_RATIO * point[2]) {
                kept.add(point);
                anchorX = point[0];
                anchorY = point[1];
            }
        }
        while (!kept.isEmpty()) {
            final double[] last = kept.get(kept.size() - 1);
            if (Math.hypot(endX - last[0], endY - last[1]) >= END_GAP_RATIO * endSpacing) {
                break;
            }
            kept.remove(kept.size() - 1);
        }
        return kept;
    }

    /**
     * Taubin low-pass and inflation passes over the span interior.
     *
     * <p>A plain Laplacian pass shrinks a closed curve by a share of its own radius. Alternating the
     * two passband steps removes the staircase without that drift, and the raw-position clamp keeps
     * every fairing result inside the pixels the border actually separates.</p>
     */
    private static void fair(final double[] xs, final double[] ys, final double[] rawX, final double[] rawY) {
        if (xs.length < 3) {
            return;
        }
        final double inflation = 1.0 / (TAUBIN_PASSBAND - 1.0 / TAUBIN_LAMBDA);
        for (int iteration = 0; iteration < TAUBIN_ITERATIONS; iteration++) {
            laplacian(xs, ys, TAUBIN_LAMBDA);
            clamp(xs, ys, rawX, rawY);
            laplacian(xs, ys, inflation);
            clamp(xs, ys, rawX, rawY);
        }
    }

    private static void laplacian(final double[] xs, final double[] ys, final double weight) {
        for (int i = 1; i < xs.length - 1; i++) {
            xs[i] += weight * (0.5 * (xs[i - 1] + xs[i + 1]) - xs[i]);
            ys[i] += weight * (0.5 * (ys[i - 1] + ys[i + 1]) - ys[i]);
        }
    }

    private static void clamp(final double[] xs, final double[] ys, final double[] rawX, final double[] rawY) {
        for (int i = 1; i < xs.length - 1; i++) {
            final double dx = xs[i] - rawX[i];
            final double dy = ys[i] - rawY[i];
            final double offset = Math.hypot(dx, dy);
            if (offset > FAIRING_CLAMP_PX) {
                final double scale = FAIRING_CLAMP_PX / offset;
                xs[i] = rawX[i] + dx * scale;
                ys[i] = rawY[i] + dy * scale;
            }
        }
    }

    private static double[] arcLengths(final double[] xs, final double[] ys) {
        final double[] arc = new double[xs.length];
        for (int i = 1; i < xs.length; i++) {
            arc[i] = arc[i - 1] + Math.hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1]);
        }
        return arc;
    }

    /**
     * Target spacing at every span vertex, smoothed into a Lipschitz field along the arc length.
     *
     * <p>A tight bend asks for a shorter chord than a straight run, yet an unsmoothed per-vertex
     * value would ring against the staircase residue. The two directional passes bound the change of
     * the spacing per pixel, so the density varies smoothly around the shape.</p>
     */
    private static double[] targetSpacings(
            final double[] xs,
            final double[] ys,
            final double[] arc,
            final double tolerance,
            final double minSpacing,
            final double maxSpacing) {
        final int count = xs.length;
        final double curveFloor = Math.min(maxSpacing, Math.max(minSpacing, CURVE_MIN_RATIO * maxSpacing));
        final double[] spacing = new double[count];
        for (int i = 0; i < count; i++) {
            final double curvature = Math.max(MIN_CURVATURE, curvature(xs, ys, i));
            final double chord = Math.sqrt(CHORD_DEVIATION_WEIGHT * tolerance / curvature);
            spacing[i] = Math.min(maxSpacing, Math.max(curveFloor, chord));
        }
        for (int i = 1; i < count; i++) {
            spacing[i] = Math.min(spacing[i], spacing[i - 1] + SPACING_LIPSCHITZ * (arc[i] - arc[i - 1]));
        }
        for (int i = count - 2; i >= 0; i--) {
            spacing[i] = Math.min(spacing[i], spacing[i + 1] + SPACING_LIPSCHITZ * (arc[i + 1] - arc[i]));
            spacing[i] = Math.min(maxSpacing, Math.max(curveFloor, spacing[i]));
        }
        return spacing;
    }

    /** Menger curvature of the three consecutive span vertices around {@code index}. */
    private static double curvature(final double[] xs, final double[] ys, final int index) {
        if (index <= 0 || index >= xs.length - 1) {
            return 0.0;
        }
        final double ax = xs[index] - xs[index - 1];
        final double ay = ys[index] - ys[index - 1];
        final double bx = xs[index + 1] - xs[index];
        final double by = ys[index + 1] - ys[index];
        final double cx = xs[index + 1] - xs[index - 1];
        final double cy = ys[index + 1] - ys[index - 1];
        final double denominator = Math.hypot(ax, ay) * Math.hypot(bx, by) * Math.hypot(cx, cy);
        if (denominator <= COSINE_FLOOR) {
            return 0.0;
        }
        return 2.0 * Math.abs(ax * by - ay * bx) / denominator;
    }

    /** Cumulative inverse spacing, whose equal steps place the resampled vertices. */
    private static double[] densityIntegral(final double[] spacing, final double[] arc) {
        final double[] density = new double[spacing.length];
        for (int i = 1; i < spacing.length; i++) {
            final double step = arc[i] - arc[i - 1];
            density[i] = density[i - 1] + 0.5 * step * (1.0 / spacing[i - 1] + 1.0 / spacing[i]);
        }
        return density;
    }

    private static double[] rotated(final double[] values, final int offset) {
        final double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[(i + offset) % values.length];
        }
        return out;
    }

    private static double[] appended(final double[] values, final double extra) {
        final double[] out = Arrays.copyOf(values, values.length + 1);
        out[values.length] = extra;
        return out;
    }
}
