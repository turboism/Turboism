package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * Nested texture regions over the alpha silhouette.
 *
 * <p>Stage one runs Felzenszwalb graph segmentation, which groups pixels that a cheap minimum
 * spanning cut keeps together. Stage two merges neighbouring regions by CIELAB mean colour with an
 * increasing threshold, which yields three strictly nested levels: every fine label belongs to
 * exactly one coarser label.</p>
 *
 * <p>Both stages weigh a crease salience field next to the colour difference. Line art of one to
 * three pixels of width therefore survives as a region border, while smooth airbrush shading still
 * merges into one region.</p>
 */
final class TextureRegionSegmenter {

    /** Felzenszwalb observation scale; the reference implementation divides it by the 8-bit range. */
    static final double GRAPH_SCALE = 100.0;

    /** Smoothing width applied before edge weights are taken. */
    static final double SMOOTHING_SIGMA = 0.6;

    /** Kernel truncation width in standard deviations. */
    static final double GAUSSIAN_TRUNCATE = 4.0;

    /** Smallest accepted component size of the initial graph cut, in pixels. */
    static final int MIN_COMPONENT_SIZE = 100;

    /** Floor of the small-region area threshold, in pixels. */
    static final int MIN_AREA_FLOOR_PIXELS = 100;

    /** Colour-distance multiplier of the finest merge level. */
    static final double FINE_THRESHOLD_SCALE = 0.65;

    /** Colour-distance multiplier of the coarsest merge level. */
    static final double COARSE_THRESHOLD_SCALE = 1.5;

    /** Divisor that makes below-threshold regions merge before their larger neighbours. */
    static final double SMALL_AREA_WEIGHT_DIVISOR = 1000.0;

    /** Highest colour distance an 8-bit sRGB pair can reach after smoothing. */
    static final double COST_SPAN = Math.sqrt(3.0);

    /** Bucket count of the counting sort that orders graph edges by cost. */
    static final int COST_BUCKETS = 1 << 16;

    /** Queue weight drift above which a stale entry is re-queued instead of merged. */
    static final double WEIGHT_DRIFT_EPS = 1.0e-8;

    /** CIELAB gradient magnitude that counts as a fully salient colour edge. */
    static final double GRADIENT_SALIENCE_SCALE = 24.0;

    /** CIELAB crease response that counts as a fully salient line-art stroke. */
    static final double RIDGE_SALIENCE_SCALE = 18.0;

    /** Weight of the crease salience inside the pixel-pair cost of the graph cut. */
    static final double RIDGE_COST_WEIGHT = 0.45;

    /** CIELAB distance that a fully salient border adds to the merge dissimilarity. */
    static final double RIDGE_MERGE_WEIGHT = 6.0;

    /** Divisors of the Scharr kernel, which keeps the gradient on the 8-bit colour scale. */
    private static final double SCHARR_KERNEL_SUM = 32.0;

    /** Scharr weight of the taps next to the centre row or column. */
    private static final double SCHARR_NEAR_WEIGHT = 10.0;

    /** Scharr weight of the corner taps. */
    private static final double SCHARR_FAR_WEIGHT = 3.0;

    /** Stroke half widths of the crease detector; one to three pixels covers line-art strokes. */
    private static final int[] RIDGE_HALF_WIDTHS = {1, 2, 3};

    /** Normal directions of the crease detector, in degrees; four axes span every pixel row. */
    private static final double[] RIDGE_AXES_DEGREES = {0.0, 45.0, 90.0, 135.0};

    private static final double DEGREES_TO_RADIANS = Math.PI / 180.0;

    private static final int CHANNELS = 3;

    private static final int RIDGE_AXIS_COUNT = RIDGE_AXES_DEGREES.length;

    private TextureRegionSegmenter() {}

    /**
     * @param coarse coarsest level, {@code level == 1}
     * @param medium middle level
     * @param fine finest level, {@code level == 3}
     */
    record Levels(int[] coarse, int[] medium, int[] fine) {

        int[] level(final int level) {
            return switch (level) {
                case 1 -> coarse;
                case 2 -> medium;
                case 3 -> fine;
                default -> throw new IllegalArgumentException("level must be within 1..3 but was " + level);
            };
        }
    }

    /**
     * Segments the solid area into nested levels.
     *
     * @param minRegionRatio fraction of the solid area below which a region merges first
     * @param contrast base CIELAB distance of the middle merge level
     */
    static Levels segment(final RegionRaster raster, final double minRegionRatio, final double contrast) {
        final int width = raster.width();
        final int height = raster.height();
        final float[] salience = salience(raster.lab(), width, height);
        final int[] graph = felzenszwalb(raster.rgb(), salience, width, height);
        final boolean[] mask = raster.mask();
        for (int i = 0; i < graph.length; i++) {
            // Region labels start at one so zero keeps one meaning: outside the silhouette.
            graph[i] = mask[i] ? graph[i] + 1 : 0;
        }
        final int[] autonomous = splitDisconnected(graph, width, height);
        final float[] lab = raster.lab();
        final int minArea = Math.max(MIN_AREA_FLOOR_PIXELS, (int) (raster.solidCount() * minRegionRatio));
        final int[] fine = merge(autonomous, lab, salience, width, height, contrast * FINE_THRESHOLD_SCALE, minArea);
        final int[] medium = merge(fine, lab, salience, width, height, contrast, 0);
        final int[] coarse = merge(medium, lab, salience, width, height, contrast * COARSE_THRESHOLD_SCALE, 0);
        return new Levels(coarse, medium, fine);
    }

    /**
     * Per-pixel crease salience in {@code [0, 1]}: the larger of the CIELAB Scharr gradient and the
     * dark or bright ridge response of a one to three pixel stroke.
     *
     * <p>Line art that a plain colour difference already separates gains a second, shape-based
     * barrier. Airbrush shading stays near zero, so its smooth ramp keeps merging.</p>
     */
    static float[] salience(final float[] lab, final int width, final int height) {
        final double[] axisX = new double[RIDGE_AXIS_COUNT];
        final double[] axisY = new double[RIDGE_AXIS_COUNT];
        for (int axis = 0; axis < RIDGE_AXIS_COUNT; axis++) {
            axisX[axis] = Math.cos(RIDGE_AXES_DEGREES[axis] * DEGREES_TO_RADIANS);
            axisY[axis] = Math.sin(RIDGE_AXES_DEGREES[axis] * DEGREES_TO_RADIANS);
        }
        final float[] out = new float[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int cell = y * width + x;
                final double gradient = gradientMagnitude(lab, width, height, x, y) / GRADIENT_SALIENCE_SCALE;
                final double ridge = ridgeResponse(lab, width, height, x, y, axisX, axisY) / RIDGE_SALIENCE_SCALE;
                out[cell] = (float) Math.min(1.0, Math.max(0.0, Math.max(gradient, ridge)));
            }
        }
        return out;
    }

    /** Scharr magnitude over the three CIELAB channels, in CIELAB units per pixel. */
    private static double gradientMagnitude(
            final float[] lab, final int width, final int height, final int x, final int y) {
        double sum = 0.0;
        for (int channel = 0; channel < CHANNELS; channel++) {
            double alongX = 0.0;
            double alongY = 0.0;
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    if (offsetX == 0 && offsetY == 0) {
                        continue;
                    }
                    final int column = Math.max(0, Math.min(width - 1, x + offsetX));
                    final int row = Math.max(0, Math.min(height - 1, y + offsetY));
                    final double weight = (offsetX == 0 || offsetY == 0) ? SCHARR_NEAR_WEIGHT : SCHARR_FAR_WEIGHT;
                    final double tapped = value(lab, column, row, width, channel) * weight;
                    alongX += tapped * offsetX;
                    alongY += tapped * offsetY;
                }
            }
            sum += (alongX * alongX + alongY * alongY) / (SCHARR_KERNEL_SUM * SCHARR_KERNEL_SUM);
        }
        return Math.sqrt(sum);
    }

    private static double value(final float[] lab, final int x, final int y, final int width, final int channel) {
        return lab[3 * (y * width + x) + channel];
    }

    /**
     * Larger of the dark and bright line response at one pixel, in CIELAB units.
     *
     * <p>The response needs a local extremum along the stroke normal at the tested half width, so
     * a plain step edge between two flat colours scores zero and cannot fake a stroke.</p>
     */
    private static double ridgeResponse(
            final float[] lab,
            final int width,
            final int height,
            final int x,
            final int y,
            final double[] axisX,
            final double[] axisY) {
        final double centre = value(lab, x, y, width, 0);
        double best = 0.0;
        for (int axis = 0; axis < RIDGE_AXIS_COUNT; axis++) {
            for (final int halfWidth : RIDGE_HALF_WIDTHS) {
                final double stepX = halfWidth * axisX[axis];
                final double stepY = halfWidth * axisY[axis];
                final double forward = sample(lab, width, height, x + stepX, y + stepY);
                final double backward = sample(lab, width, height, x - stepX, y - stepY);
                final double dark = Math.min(forward - centre, backward - centre);
                final double bright = Math.min(centre - forward, centre - backward);
                best = Math.max(best, Math.max(dark, bright));
            }
        }
        return best;
    }

    /** Bilinear luminance sample at a fractional pixel position; edges clamp to the border pixel. */
    private static double sample(final float[] lab, final int width, final int height, final double x, final double y) {
        final double clampedX = Math.max(0.0, Math.min(width - 1.0, x));
        final double clampedY = Math.max(0.0, Math.min(height - 1.0, y));
        final int left = (int) Math.floor(clampedX);
        final int top = (int) Math.floor(clampedY);
        final int right = Math.min(width - 1, left + 1);
        final int bottom = Math.min(height - 1, top + 1);
        final double fractionX = clampedX - left;
        final double fractionY = clampedY - top;
        final double upper =
                value(lab, left, top, width, 0) * (1.0 - fractionX) + value(lab, right, top, width, 0) * fractionX;
        final double lower = value(lab, left, bottom, width, 0) * (1.0 - fractionX)
                + value(lab, right, bottom, width, 0) * fractionX;
        return upper * (1.0 - fractionY) + lower * fractionY;
    }

    /**
     * Felzenszwalb minimum-spanning-cut segmentation over an 8-connected pixel graph.
     *
     * @return labels {@code 0..k-1} in root order, one per pixel
     */
    private static int[] felzenszwalb(final float[] rgb, final float[] salience, final int width, final int height) {
        final float[][] planes = smoothedPlanes(rgb, width, height);
        final EdgeGrid grid = new EdgeGrid(planes, salience, width, height);
        final int pixels = width * height;
        final int[] parent = new int[pixels];
        final int[] size = new int[pixels];
        final double[] innerCost = new double[pixels];
        for (int i = 0; i < pixels; i++) {
            parent[i] = i;
            size[i] = 1;
        }
        final double scale = GRAPH_SCALE / 255.0;
        final int[] order = orderByCost(grid);
        for (final int edge : order) {
            final int rootA = find(parent, grid.source(edge));
            final int rootB = find(parent, grid.target(edge));
            if (rootA == rootB) {
                continue;
            }
            final double cost = grid.cost(edge);
            final double limitA = innerCost[rootA] + scale / size[rootA];
            final double limitB = innerCost[rootB] + scale / size[rootB];
            if (cost < Math.min(limitA, limitB)) {
                final int merged = join(parent, rootA, rootB);
                size[merged] = size[rootA] + size[rootB];
                innerCost[merged] = cost;
            }
        }
        for (final int edge : order) {
            final int rootA = find(parent, grid.source(edge));
            final int rootB = find(parent, grid.target(edge));
            if (rootA == rootB) {
                continue;
            }
            if (size[rootA] < MIN_COMPONENT_SIZE || size[rootB] < MIN_COMPONENT_SIZE) {
                final int merged = join(parent, rootA, rootB);
                size[merged] = size[rootA] + size[rootB];
            }
        }
        return compact(parent, pixels);
    }

    /** Splits pixels that share a label but touch only at a corner, keeping regions autonomous. */
    private static int[] splitDisconnected(final int[] labels, final int width, final int height) {
        final int[] out = new int[labels.length];
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        int next = 0;
        for (int start = 0; start < labels.length; start++) {
            if (out[start] != 0 || labels[start] == 0) {
                continue;
            }
            final int label = labels[start];
            next++;
            out[start] = next;
            queue.add(start);
            while (!queue.isEmpty()) {
                final int cell = queue.poll();
                final int x = cell % width;
                final int y = cell / width;
                if (x > 0) {
                    visit(labels, out, queue, cell - 1, label, next);
                }
                if (x + 1 < width) {
                    visit(labels, out, queue, cell + 1, label, next);
                }
                if (y > 0) {
                    visit(labels, out, queue, cell - width, label, next);
                }
                if (y + 1 < height) {
                    visit(labels, out, queue, cell + width, label, next);
                }
            }
        }
        return out;
    }

    private static void visit(
            final int[] labels,
            final int[] out,
            final ArrayDeque<Integer> queue,
            final int cell,
            final int label,
            final int assigned) {
        if (out[cell] == 0 && labels[cell] == label) {
            out[cell] = assigned;
            queue.add(cell);
        }
    }

    /**
     * Agglomerates neighbouring labels whose CIELAB mean distance stays under the threshold.
     *
     * <p>The dissimilarity of two labels adds the mean crease salience of the pixels they share, so
     * a drawn line survives the coarsest level while a smooth shading ramp still merges.</p>
     *
     * @param minArea regions below this pixel count merge first, at a reduced weight
     */
    private static int[] merge(
            final int[] labels,
            final float[] lab,
            final float[] salience,
            final int width,
            final int height,
            final double threshold,
            final int minArea) {
        final int labelCount = maxLabel(labels) + 1;
        final long[] counts = new long[labelCount];
        final double[] sumL = new double[labelCount];
        final double[] sumA = new double[labelCount];
        final double[] sumB = new double[labelCount];
        for (int i = 0; i < labels.length; i++) {
            final int label = labels[i];
            counts[label]++;
            sumL[label] += lab[3 * i];
            sumA[label] += lab[3 * i + 1];
            sumB[label] += lab[3 * i + 2];
        }
        final List<Set<Integer>> adjacency = new ArrayList<>(labelCount);
        for (int i = 0; i < labelCount; i++) {
            adjacency.add(new HashSet<>());
        }
        final Map<Long, Border> borders = new HashMap<>();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int cell = y * width + x;
                if (x + 1 < width) {
                    collect(adjacency, borders, labels, salience, cell, cell + 1);
                }
                if (y + 1 < height) {
                    collect(adjacency, borders, labels, salience, cell, cell + width);
                }
            }
        }
        final int[] parent = new int[labelCount];
        for (int i = 0; i < labelCount; i++) {
            parent[i] = i;
        }
        final PriorityQueue<Merge> queue = new PriorityQueue<>();
        for (int a = 1; a < labelCount; a++) {
            for (final int b : adjacency.get(a)) {
                if (a < b) {
                    queue.add(new Merge(weight(a, b, counts, sumL, sumA, sumB, borders, minArea), a, b));
                }
            }
        }
        while (!queue.isEmpty()) {
            final Merge candidate = queue.poll();
            final int a = candidate.a();
            final int b = candidate.b();
            if (parent[a] != a || parent[b] != b || !adjacency.get(a).contains(b)) {
                continue;
            }
            final double current = weight(a, b, counts, sumL, sumA, sumB, borders, minArea);
            if (Math.abs(candidate.weight() - current) > WEIGHT_DRIFT_EPS) {
                queue.add(new Merge(current, a, b));
                continue;
            }
            if (current > threshold) {
                queue.clear();
                break;
            }
            parent[b] = a;
            counts[a] += counts[b];
            sumL[a] += sumL[b];
            sumA[a] += sumA[b];
            sumB[a] += sumB[b];
            final Set<Integer> neighbours = new HashSet<>(adjacency.get(a));
            neighbours.addAll(adjacency.get(b));
            neighbours.remove(a);
            neighbours.remove(b);
            adjacency.set(a, neighbours);
            for (final int neighbour : neighbours) {
                adjacency.get(neighbour).remove(b);
                adjacency.get(neighbour).remove(a);
                adjacency.get(neighbour).add(a);
                borders.put(
                        pairKey(a, neighbour),
                        Border.merge(borders.remove(pairKey(b, neighbour)), borders.get(pairKey(a, neighbour))));
                queue.add(new Merge(
                        weight(a, neighbour, counts, sumL, sumA, sumB, borders, minArea),
                        Math.min(a, neighbour),
                        Math.max(a, neighbour)));
            }
        }
        return relabel(labels, parent);
    }

    /**
     * Records one adjacent pixel pair for the merge graph.
     *
     * <p>The pair contributes its crease salience, so the merge weight of a border can tell a drawn
     * line from a smooth shading ramp.</p>
     */
    private static void collect(
            final List<Set<Integer>> adjacency,
            final Map<Long, Border> borders,
            final int[] labels,
            final float[] salience,
            final int first,
            final int second) {
        final int low = Math.min(labels[first], labels[second]);
        final int high = Math.max(labels[first], labels[second]);
        if (low == high) {
            return;
        }
        link(adjacency, low, high);
        if (low == 0) {
            return;
        }
        borders.merge(pairKey(low, high), new Border(salience[first] + salience[second], 2L), Border::merge);
    }

    private static long pairKey(final int a, final int b) {
        final int low = Math.min(a, b);
        final int high = Math.max(a, b);
        return ((long) low << 32) | (high & 0xFFFF_FFFFL);
    }

    /** Accumulated crease salience over the pixels that two labels share. */
    private record Border(double salience, long pixels) {

        static Border merge(final Border first, final Border second) {
            if (first == null) {
                return second == null ? new Border(0.0, 0L) : second;
            }
            return second == null ? first : new Border(first.salience + second.salience, first.pixels + second.pixels);
        }

        double mean() {
            return pixels == 0L ? 0.0 : salience / pixels;
        }
    }

    private static void link(final List<Set<Integer>> adjacency, final int a, final int b) {
        if (a == b || a == 0 || b == 0) {
            return;
        }
        adjacency.get(a).add(b);
        adjacency.get(b).add(a);
    }

    private static double weight(
            final int a,
            final int b,
            final long[] counts,
            final double[] sumL,
            final double[] sumA,
            final double[] sumB,
            final Map<Long, Border> borders,
            final int minArea) {
        final double dl = sumL[a] / counts[a] - sumL[b] / counts[b];
        final double da = sumA[a] / counts[a] - sumA[b] / counts[b];
        final double db = sumB[a] / counts[a] - sumB[b] / counts[b];
        final double colour = Math.sqrt(dl * dl + da * da + db * db);
        final Border border = borders.get(pairKey(a, b));
        final double distance = colour + RIDGE_MERGE_WEIGHT * (border == null ? 0.0 : border.mean());
        return Math.min(counts[a], counts[b]) < minArea ? distance / SMALL_AREA_WEIGHT_DIVISOR : distance;
    }

    private static int[] relabel(final int[] labels, final int[] parent) {
        final boolean[] present = new boolean[parent.length];
        final int[] resolved = new int[labels.length];
        // Zero must stay the outside marker even when the crop holds no empty pixel.
        present[find(parent, 0)] = true;
        for (int i = 0; i < labels.length; i++) {
            resolved[i] = find(parent, labels[i]);
            present[resolved[i]] = true;
        }
        final int[] rank = new int[parent.length];
        int next = 0;
        for (int root = 0; root < parent.length; root++) {
            if (present[root]) {
                rank[root] = next++;
            }
        }
        final int[] out = new int[labels.length];
        for (int i = 0; i < labels.length; i++) {
            out[i] = rank[resolved[i]];
        }
        return out;
    }

    private static int[] compact(final int[] parent, final int pixels) {
        final int[] root = new int[pixels];
        final boolean[] present = new boolean[pixels];
        for (int i = 0; i < pixels; i++) {
            root[i] = find(parent, i);
            present[root[i]] = true;
        }
        final int[] rank = new int[pixels];
        int next = 0;
        for (int i = 0; i < pixels; i++) {
            if (present[i]) {
                rank[i] = next++;
            }
        }
        final int[] out = new int[pixels];
        for (int i = 0; i < pixels; i++) {
            out[i] = rank[root[i]];
        }
        return out;
    }

    private static int find(final int[] parent, final int start) {
        int root = start;
        while (parent[root] != root) {
            root = parent[root];
        }
        int cursor = start;
        while (parent[cursor] != root) {
            final int next = parent[cursor];
            parent[cursor] = root;
            cursor = next;
        }
        return root;
    }

    private static int join(final int[] parent, final int a, final int b) {
        if (a < b) {
            parent[b] = a;
            return a;
        }
        parent[a] = b;
        return b;
    }

    private static int maxLabel(final int[] labels) {
        int max = 0;
        for (final int label : labels) {
            max = Math.max(max, label);
        }
        return max;
    }

    private static float[][] smoothedPlanes(final float[] rgb, final int width, final int height) {
        final int radius = (int) (GAUSSIAN_TRUNCATE * SMOOTHING_SIGMA + 0.5);
        final double[] kernel = new double[2 * radius + 1];
        double total = 0.0;
        for (int i = -radius; i <= radius; i++) {
            final double value = Math.exp(-(i * i) / (2.0 * SMOOTHING_SIGMA * SMOOTHING_SIGMA));
            kernel[i + radius] = value;
            total += value;
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= total;
        }
        final float[][] planes = new float[CHANNELS][width * height];
        final float[] line = new float[Math.max(width, height)];
        for (int channel = 0; channel < CHANNELS; channel++) {
            final float[] source = new float[width * height];
            for (int i = 0; i < source.length; i++) {
                source[i] = rgb[3 * i + channel];
            }
            final float[] horizontal = new float[width * height];
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    double sum = 0.0;
                    for (int k = -radius; k <= radius; k++) {
                        sum += kernel[k + radius] * source[y * width + mirror(x + k, width)];
                    }
                    horizontal[y * width + x] = (float) sum;
                }
            }
            final float[] target = planes[channel];
            for (int x = 0; x < width; x++) {
                for (int y = 0; y < height; y++) {
                    line[y] = horizontal[y * width + x];
                }
                for (int y = 0; y < height; y++) {
                    double sum = 0.0;
                    for (int k = -radius; k <= radius; k++) {
                        sum += kernel[k + radius] * line[mirror(y + k, height)];
                    }
                    target[y * width + x] = (float) sum;
                }
            }
        }
        return planes;
    }

    /** Half-sample symmetric index mirroring, which keeps the silhouette edge unbiased. */
    private static int mirror(final int index, final int size) {
        if (index < 0) {
            return -index - 1;
        }
        if (index >= size) {
            return 2 * size - index - 1;
        }
        return index;
    }

    private static int[] orderByCost(final EdgeGrid grid) {
        final int[] counts = new int[COST_BUCKETS + 1];
        for (int edge = 0; edge < grid.count(); edge++) {
            counts[bucket(grid.cost(edge))]++;
        }
        final int[] offsets = new int[COST_BUCKETS + 1];
        int running = 0;
        for (int i = 0; i < COST_BUCKETS; i++) {
            offsets[i] = running;
            running += counts[i];
        }
        final int[] order = new int[grid.count()];
        for (int edge = 0; edge < grid.count(); edge++) {
            order[offsets[bucket(grid.cost(edge))]++] = edge;
        }
        return order;
    }

    private static int bucket(final double cost) {
        final int index = (int) (cost / COST_SPAN * COST_BUCKETS);
        return Math.max(0, Math.min(COST_BUCKETS - 1, index));
    }

    /** Merge candidate; ordering by weight then indices keeps the loop deterministic. */
    private record Merge(double weight, int a, int b) implements Comparable<Merge> {

        @Override
        public int compareTo(final Merge other) {
            int result = Double.compare(weight, other.weight);
            if (result != 0) {
                return result;
            }
            result = Integer.compare(a, other.a);
            return result != 0 ? result : Integer.compare(b, other.b);
        }
    }

    /** Arithmetic mapping from a linear 8-connected edge index to its pixel pair and cost. */
    private static final class EdgeGrid {

        private final float[] red;
        private final float[] green;
        private final float[] blue;
        private final float[] salience;
        private final int width;
        private final int height;
        private final int rightCount;
        private final int downCount;
        private final int diagonalCount;

        EdgeGrid(final float[][] planes, final float[] salience, final int width, final int height) {
            this.red = planes[0];
            this.green = planes[1];
            this.blue = planes[2];
            this.salience = salience;
            this.width = width;
            this.height = height;
            this.rightCount = height * Math.max(0, width - 1);
            this.downCount = Math.max(0, height - 1) * width;
            this.diagonalCount = Math.max(0, height - 1) * Math.max(0, width - 1);
        }

        int count() {
            return rightCount + downCount + 2 * diagonalCount;
        }

        int source(final int edge) {
            if (edge < rightCount) {
                final int y = edge / (width - 1);
                return y * width + edge % (width - 1);
            }
            final int offset = edge - rightCount;
            if (offset < downCount) {
                final int y = offset / width;
                return y * width + offset % width;
            }
            final int diagonalOffset = offset - downCount;
            if (diagonalOffset < diagonalCount) {
                final int y = diagonalOffset / (width - 1);
                return (y + 1) * width + diagonalOffset % (width - 1) + 1;
            }
            final int other = diagonalOffset - diagonalCount;
            final int y = other / (width - 1);
            return y * width + other % (width - 1) + 1;
        }

        int target(final int edge) {
            if (edge < rightCount) {
                return source(edge) + 1;
            }
            final int offset = edge - rightCount;
            if (offset < downCount) {
                return source(edge) + width;
            }
            final int diagonalOffset = offset - downCount;
            if (diagonalOffset < diagonalCount) {
                final int y = diagonalOffset / (width - 1);
                return y * width + diagonalOffset % (width - 1);
            }
            final int other = diagonalOffset - diagonalCount;
            final int y = other / (width - 1);
            return (y + 1) * width + other % (width - 1);
        }

        double cost(final int edge) {
            final int a = source(edge);
            final int b = target(edge);
            final double dr = red[a] - red[b];
            final double dg = green[a] - green[b];
            final double db = blue[a] - blue[b];
            final double crease = RIDGE_COST_WEIGHT * 0.5 * (salience[a] + salience[b]);
            return Math.sqrt(dr * dr + dg * dg + db * db + crease * crease);
        }
    }
}
