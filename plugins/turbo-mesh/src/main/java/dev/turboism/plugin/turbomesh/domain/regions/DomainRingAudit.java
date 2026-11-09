package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Verifies that a ring set really encloses the material domain.
 *
 * <p>Domain rings drive even-odd triangle classification, so a ring set that lost its silhouette
 * makes the triangulator discard the material and keep the air. Chaining edges into rings cannot
 * detect that loss on its own: an open chain and a closed ring look the same to a caller that only
 * receives the closed loops. This audit therefore compares the ring set with the domain it must
 * reproduce, and the planner treats every failure as a reason to retry without the questionable
 * stage.</p>
 */
final class DomainRingAudit {

    /** Share of the reference domain area below which the ring set counts as truncated. */
    static final double AREA_TOLERANCE_RATIO = 0.02;

    /** Smallest number of distinct vertices a usable ring needs. */
    private static final int MIN_RING_VERTICES = 3;

    private DomainRingAudit() {}

    /** Outcome of one audit; {@link #PASSED} is the only accepting value. */
    enum Verdict {
        PASSED,
        EMPTY,
        DEGENERATE_RING,
        NON_SIMPLE_RING,
        CROSSING_RINGS,
        AREA_MISMATCH
    }

    /**
     * @param referenceArea area of the domain the rings must reproduce, in square pixels
     */
    static Verdict check(final SiteGraph.Snapshot snapshot, final List<int[]> rings, final double referenceArea) {
        if (rings.isEmpty()) {
            return Verdict.EMPTY;
        }
        for (final int[] ring : rings) {
            if (distinctVertices(ring) < MIN_RING_VERTICES || Math.abs(ringArea(snapshot, ring)) <= 0.0) {
                return Verdict.DEGENERATE_RING;
            }
        }
        for (final int[] ring : rings) {
            if (!isSimple(snapshot, ring)) {
                return Verdict.NON_SIMPLE_RING;
            }
        }
        for (int i = 0; i < rings.size(); i++) {
            for (int j = i + 1; j < rings.size(); j++) {
                if (crosses(snapshot, rings.get(i), rings.get(j))) {
                    return Verdict.CROSSING_RINGS;
                }
            }
        }
        if (!agreesWithReference(snapshot, rings, referenceArea)) {
            return Verdict.AREA_MISMATCH;
        }
        return Verdict.PASSED;
    }

    /**
     * Net enclosed area of the ring set: material rings count, hole rings subtract.
     *
     * <p>Nesting depth decides which is which, so disjoint parts each keep their own area. Ring order
     * and ring size cannot answer that question.</p>
     */
    static double enclosedArea(final SiteGraph.Snapshot snapshot, final List<int[]> rings) {
        final List<double[][]> coordinates = new ArrayList<>(rings.size());
        for (final int[] ring : rings) {
            final double[] xs = new double[ring.length];
            final double[] ys = new double[ring.length];
            for (int i = 0; i < ring.length; i++) {
                xs[i] = snapshot.xs()[ring[i]];
                ys[i] = snapshot.ys()[ring[i]];
            }
            coordinates.add(new double[][] {xs, ys});
        }
        final int[] depths = RingNesting.depths(coordinates);
        double total = 0.0;
        for (int i = 0; i < rings.size(); i++) {
            final double area = Math.abs(ringArea(snapshot, rings.get(i)));
            total += depths[i] % 2 == 0 ? area : -area;
        }
        return total;
    }

    /** Signed shoelace area of one ring; the sign keeps the winding of the input loop. */
    static double ringArea(final SiteGraph.Snapshot snapshot, final int[] ring) {
        return ringArea(snapshot.xs(), snapshot.ys(), ring);
    }

    /** Signed shoelace area of one ring over raw coordinates. */
    static double ringArea(final double[] xs, final double[] ys, final int[] ring) {
        double sum = 0.0;
        for (int i = 0; i < ring.length; i++) {
            final int next = ring[(i + 1) % ring.length];
            sum += xs[ring[i]] * ys[next] - xs[next] * ys[ring[i]];
        }
        return sum * 0.5;
    }

    /**
     * Whether a site merge may replace the rings of {@code before} with {@code candidate}.
     *
     * <p>A merge that pushes two ring vertices onto one position folds the loop into a bowtie.
     * The bowtie still closes, so face counting cannot see it, but it reverses the even-odd status
     * of one lobe and the triangulator then drops real material. The check therefore inspects the
     * rings around the surviving vertex only, because every other ring edge keeps its geometry.</p>
     *
     * @param mergedAway site index that the merge deactivates
     * @param mergedInto site index that receives the mass of {@code mergedAway}
     */
    static boolean acceptsRingRewrite(
            final double[] xs,
            final double[] ys,
            final List<int[]> before,
            final List<int[]> candidate,
            final int mergedAway,
            final int mergedInto) {
        if (candidate.size() < before.size()) {
            return false;
        }
        for (int i = 0; i < candidate.size(); i++) {
            if (!touches(before.get(i), mergedAway)) {
                continue;
            }
            final int[] ring = candidate.get(i);
            if (occurrences(ring, mergedInto) > 1) {
                return false;
            }
            if (!staysSimpleAt(xs, ys, ring, mergedInto)) {
                return false;
            }
        }
        return true;
    }

    private static boolean touches(final int[] ring, final int site) {
        for (final int vertex : ring) {
            if (vertex == site) {
                return true;
            }
        }
        return false;
    }

    private static int occurrences(final int[] ring, final int site) {
        int count = 0;
        for (final int vertex : ring) {
            if (vertex == site) {
                count++;
            }
        }
        return count;
    }

    /** Tests only the segments that the merge moved against all others. */
    private static boolean staysSimpleAt(final double[] xs, final double[] ys, final int[] ring, final int moved) {
        final int size = ring.length;
        for (int i = 0; i < size; i++) {
            final int iNext = (i + 1) % size;
            if (ring[i] != moved && ring[iNext] != moved) {
                continue;
            }
            for (int j = 0; j < size; j++) {
                final int jNext = (j + 1) % size;
                if (i == j || jNext == i || iNext == j) {
                    continue;
                }
                if (shareVertex(ring, i, iNext, ring, j, jNext)) {
                    continue;
                }
                if (properlyCrosses(xs, ys, ring[i], ring[iNext], ring[j], ring[jNext])) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean agreesWithReference(
            final SiteGraph.Snapshot snapshot, final List<int[]> rings, final double referenceArea) {
        if (!(referenceArea > 0.0)) {
            return false;
        }
        final double deviation = Math.abs(enclosedArea(snapshot, rings) - referenceArea) / referenceArea;
        return deviation <= AREA_TOLERANCE_RATIO;
    }

    private static int distinctVertices(final int[] ring) {
        final Set<Integer> distinct = new HashSet<>(ring.length * 2);
        for (final int vertex : ring) {
            distinct.add(vertex);
        }
        return distinct.size();
    }

    /**
     * A ring may touch itself at a pinch vertex that near-point merging created, because such a
     * touch keeps both lobes inside the even-odd domain. A proper crossing between two non adjacent
     * ring segments is different: it flips one lobe outside the domain, so it rejects the ring.
     */
    private static boolean isSimple(final SiteGraph.Snapshot snapshot, final int[] ring) {
        return isSimple(snapshot.xs(), snapshot.ys(), ring);
    }

    private static boolean isSimple(final double[] xs, final double[] ys, final int[] ring) {
        final int size = ring.length;
        for (int i = 0; i < size; i++) {
            final int iNext = (i + 1) % size;
            for (int j = i + 1; j < size; j++) {
                final int jNext = (j + 1) % size;
                if (shareVertex(ring, i, iNext, ring, j, jNext) || adjacent(i, j, size)) {
                    continue;
                }
                if (properlyCrosses(xs, ys, ring[i], ring[iNext], ring[j], ring[jNext])) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean crosses(final SiteGraph.Snapshot first, final int[] a, final int[] b) {
        for (int i = 0; i < a.length; i++) {
            final int iNext = (i + 1) % a.length;
            for (int j = 0; j < b.length; j++) {
                final int jNext = (j + 1) % b.length;
                if (shareVertex(a, i, iNext, b, j, jNext)) {
                    continue;
                }
                if (properlyCrosses(first.xs(), first.ys(), a[i], a[iNext], b[j], b[jNext])) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean properlyCrosses(
            final double[] xs, final double[] ys, final int a, final int b, final int c, final int d) {
        if (!boxesTouch(xs, ys, a, b, c, d)) {
            return false;
        }
        return PixelGeometry.properCrossing(xs[a], ys[a], xs[b], ys[b], xs[c], ys[c], xs[d], ys[d]);
    }

    /** Cheap rejection before the orientation tests run. */
    private static boolean boxesTouch(
            final double[] xs, final double[] ys, final int a, final int b, final int c, final int d) {
        return Math.min(xs[a], xs[b]) <= Math.max(xs[c], xs[d])
                && Math.min(xs[c], xs[d]) <= Math.max(xs[a], xs[b])
                && Math.min(ys[a], ys[b]) <= Math.max(ys[c], ys[d])
                && Math.min(ys[c], ys[d]) <= Math.max(ys[a], ys[b]);
    }

    private static boolean shareVertex(
            final int[] first,
            final int firstIndex,
            final int firstNext,
            final int[] second,
            final int secondIndex,
            final int secondNext) {
        final int[] firstEnds = {first[firstIndex], first[firstNext]};
        final int[] secondEnds = {second[secondIndex], second[secondNext]};
        for (final int one : firstEnds) {
            for (final int other : secondEnds) {
                if (one == other) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean adjacent(final int first, final int second, final int size) {
        final int gap = Math.abs(first - second);
        return gap <= 1 || gap == size - 1;
    }
}
