package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Extracts every shared pixel border exactly once as polyline chains.
 *
 * <p>Region borders are cut into chains at junctions, lightly low-passed to remove single-pixel
 * staircase noise, simplified, and resampled. Each chain keeps the pair of labels it separates, so
 * the mesh builder never has to guess which regions two neighbouring cells belong to.</p>
 */
final class SharedBoundaryPaths {

    /** Cosine above which a vertex sits on a locally straight run and may be smoothed. */
    static final double LOWPASS_MIN_COSINE = 0.65;

    /** Vertices this close to a chain end stay untouched so junctions never move. */
    static final int LOWPASS_END_SKIP = 4;

    /** Shortest chain that receives low-pass filtering. */
    static final int LOWPASS_MIN_POINTS = 9;

    /** Half width of the smoothing window, in vertices. */
    private static final int LOWPASS_REACH = 2;

    /** Normalisation of the {1,2,3,2,1} staircase filter. */
    private static final double LOWPASS_WEIGHT_SUM = 9.0;

    private static final double COSINE_FLOOR = 1.0e-9;

    private SharedBoundaryPaths() {}

    /**
     * @param labels region labels of the selected level, {@code 0} outside the silhouette
     * @param tolerance Douglas–Peucker tolerance in pixels
     * @param spacing upper bound of the distance between consecutive emitted points
     */
    static List<BoundaryPath> extract(
            final int[] labels, final int width, final int height, final double tolerance, final double spacing) {
        final Map<Long, List<int[]>> groups = new TreeMap<>();
        collectHorizontal(labels, width, height, groups);
        collectVertical(labels, width, height, groups);
        final List<BoundaryPath> paths = new ArrayList<>();
        for (final Map.Entry<Long, List<int[]>> group : groups.entrySet()) {
            final int labelA = (int) (group.getKey() >>> 32);
            final int labelB = (int) (group.getKey() & 0xFFFF_FFFFL);
            for (final int[] chain : traceChains(group.getValue())) {
                final Polyline line =
                        toPolyline(chain, width + 1).simplified(tolerance).resampled(spacing);
                if (line.size() >= 2 && line.length() > 0.0) {
                    paths.add(new BoundaryPath(labelA, labelB, line));
                }
            }
        }
        return paths;
    }

    private static void collectHorizontal(
            final int[] labels, final int width, final int height, final Map<Long, List<int[]>> groups) {
        for (int y = 0; y <= height; y++) {
            for (int x = 0; x < width; x++) {
                final int above = y > 0 ? labels[(y - 1) * width + x] : 0;
                final int below = y < height ? labels[y * width + x] : 0;
                if (above != below) {
                    add(groups, above, below, y * (width + 1) + x, y * (width + 1) + x + 1);
                }
            }
        }
    }

    private static void collectVertical(
            final int[] labels, final int width, final int height, final Map<Long, List<int[]>> groups) {
        for (int x = 0; x <= width; x++) {
            for (int y = 0; y < height; y++) {
                final int left = x > 0 ? labels[y * width + x - 1] : 0;
                final int right = x < width ? labels[y * width + x] : 0;
                if (left != right) {
                    add(groups, left, right, y * (width + 1) + x, (y + 1) * (width + 1) + x);
                }
            }
        }
    }

    private static void add(
            final Map<Long, List<int[]>> groups, final int first, final int second, final int from, final int to) {
        final int low = Math.min(first, second);
        final int high = Math.max(first, second);
        groups.computeIfAbsent(((long) low << 32) | (high & 0xFFFF_FFFFL), ignored -> new ArrayList<>())
                .add(new int[] {from, to});
    }

    /**
     * Walks each border chain once, starting at junction nodes so a shared border stays unsplit
     * beyond the junction it actually meets at.
     */
    private static List<int[]> traceChains(final List<int[]> edges) {
        final Map<Integer, List<Integer>> adjacency = new HashMap<>();
        for (final int[] edge : edges) {
            adjacency.computeIfAbsent(edge[0], ignored -> new ArrayList<>()).add(edge[1]);
            adjacency.computeIfAbsent(edge[1], ignored -> new ArrayList<>()).add(edge[0]);
        }
        final List<Integer> starts = new ArrayList<>(adjacency.keySet());
        starts.sort((first, second) -> {
            final boolean looseFirst = adjacency.get(first).size() != 2;
            final boolean looseSecond = adjacency.get(second).size() != 2;
            if (looseFirst != looseSecond) {
                return looseFirst ? -1 : 1;
            }
            return Integer.compare(first, second);
        });
        final Set<Long> unused = new HashSet<>();
        for (final int[] edge : edges) {
            unused.add(edgeKey(edge[0], edge[1]));
        }
        final List<int[]> chains = new ArrayList<>();
        for (final int start : starts) {
            for (final int next : adjacency.get(start)) {
                if (!unused.remove(edgeKey(start, next))) {
                    continue;
                }
                final List<Integer> chain = new ArrayList<>();
                chain.add(start);
                int current = next;
                while (true) {
                    chain.add(current);
                    if (current == start || adjacency.get(current).size() != 2) {
                        break;
                    }
                    int chosen = -1;
                    for (final int candidate : adjacency.get(current)) {
                        if (unused.contains(edgeKey(current, candidate))) {
                            chosen = candidate;
                            break;
                        }
                    }
                    if (chosen < 0) {
                        break;
                    }
                    unused.remove(edgeKey(chain.get(chain.size() - 1), chosen));
                    current = chosen;
                }
                chains.add(toArray(chain));
            }
        }
        return chains;
    }

    private static long edgeKey(final int a, final int b) {
        return ((long) Math.min(a, b) << 32) | (Math.max(a, b) & 0xFFFF_FFFFL);
    }

    private static int[] toArray(final List<Integer> values) {
        final int[] out = new int[values.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = values.get(i);
        }
        return out;
    }

    /** Maps corner-lattice nodes to pixel-centre coordinates and smooths staircase noise. */
    private static Polyline toPolyline(final int[] chain, final int cornerWidth) {
        final double[] xs = new double[chain.length];
        final double[] ys = new double[chain.length];
        for (int i = 0; i < chain.length; i++) {
            xs[i] = (chain[i] % cornerWidth) - 0.5;
            ys[i] = (chain[i] / cornerWidth) - 0.5;
        }
        final boolean closed = chain.length > 2 && chain[0] == chain[chain.length - 1];
        final Polyline raw = closed ? Polyline.closedFromRepeated(xs, ys) : new Polyline(xs, ys, false);
        return lowPass(raw);
    }

    /**
     * Replaces staircase steps with a light average, but only where the chain already runs
     * straight; multi-pixel corners survive because their neighbour directions disagree.
     */
    private static Polyline lowPass(final Polyline line) {
        final int size = line.size();
        if (size < LOWPASS_MIN_POINTS) {
            return line;
        }
        final double[] xs = new double[size];
        final double[] ys = new double[size];
        for (int i = 0; i < size; i++) {
            xs[i] = line.x(i);
            ys[i] = line.y(i);
        }
        for (int i = LOWPASS_END_SKIP; i < size - LOWPASS_END_SKIP; i++) {
            final double ax = line.x(i) - line.x(i - LOWPASS_END_SKIP);
            final double ay = line.y(i) - line.y(i - LOWPASS_END_SKIP);
            final double bx = line.x(i + LOWPASS_END_SKIP) - line.x(i);
            final double by = line.y(i + LOWPASS_END_SKIP) - line.y(i);
            final double denominator = Math.max(COSINE_FLOOR, Math.hypot(ax, ay) * Math.hypot(bx, by));
            if ((ax * bx + ay * by) / denominator <= LOWPASS_MIN_COSINE) {
                continue;
            }
            xs[i] = smoothed(line, i, true);
            ys[i] = smoothed(line, i, false);
        }
        return new Polyline(xs, ys, line.closed());
    }

    private static double smoothed(final Polyline line, final int index, final boolean horizontal) {
        final int size = line.size();
        double sum = 0.0;
        for (int k = -LOWPASS_REACH; k <= LOWPASS_REACH; k++) {
            final int source = resolve(index + k, size, line.closed());
            final double weight = LOWPASS_REACH + 1 - Math.abs(k);
            sum += weight * (horizontal ? line.x(source) : line.y(source));
        }
        return sum / LOWPASS_WEIGHT_SUM;
    }

    private static int resolve(final int index, final int size, final boolean closed) {
        if (closed) {
            return ((index % size) + size) % size;
        }
        return Math.max(0, Math.min(size - 1, index));
    }
}
