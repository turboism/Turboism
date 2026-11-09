package dev.turboism.plugin.turbomesh.domain.regions;

import java.util.Arrays;
import java.util.Random;

/**
 * Fills the interior of every region with a distance-graded Poisson disk sampling.
 *
 * <p>Dense next to the border and sparse in the flat middle: the inhibition radius follows the
 * distance field, so a triangle keeps its shape whether it sits against the silhouette or in an open
 * lobe. Medial-axis ridges seed the narrow corridors first, because a corridor has no flat middle to
 * grade towards.</p>
 */
final class RegionInteriorFill {

    /** Fraction of the local boundary spacing that sets the border inhibition radius. */
    static final double EDGE_RADIUS_RATIO = 0.68;

    /** Upper bound of the border inhibition radius, as a fraction of the maximum spacing. */
    static final double EDGE_RADIUS_CAP_RATIO = 0.78;

    /** Growth of the inhibition radius per pixel away from the border. */
    static final double GRADE_FACTOR = 0.50;

    /** Upper bound of the inhibition radius, as a fraction of the maximum spacing. */
    static final double MAX_RADIUS_RATIO = 1.35;

    /** Lower bound of the inhibition radius, as a fraction of the maximum spacing. */
    static final double MIN_RADIUS_RATIO = 0.45;

    /** Wall clearance that a candidate needs, as a fraction of its inhibition radius. */
    static final double WALL_CLEARANCE_RATIO = 0.58;

    /** Share of the summed inhibition radii that separates two accepted sites. */
    static final double PAIR_SEPARATION_RATIO = 0.5;

    /** Half amplitude of the grid-breaking offset, in pixels. */
    static final double JITTER_PX = 0.5;

    /** Height of one distance layer, as a fraction of the minimum spacing. */
    static final double LAYER_HEIGHT_RATIO = 0.5;

    /** Golden-ratio conjugate of the deterministic jitter sequence. */
    static final double GOLDEN_RATIO_CONJUGATE = 0.618_033_988_749_894_9;

    /** Distance tolerance of the medial-axis detector, in pixels. */
    static final double RIDGE_TOLERANCE_PX = 0.5;

    /** Seed of the deterministic candidate shuffle, which keeps every run identical. */
    static final long SHUFFLE_SEED = 0x5EED_1234L;

    private RegionInteriorFill() {}

    /**
     * Adds interior sites to the plane graph.
     *
     * @param graph plane graph that already holds every border path
     * @param labels region labels of the selected level, {@code 0} outside the silhouette
     * @param domain material outline; membership follows its even-odd ring rule
     * @param maxSpacing upper bound of the distance between two boundary sites, in pixels
     * @param minSpacing lower bound of the distance between two sites, in pixels
     */
    static void fill(
            final SiteGraph graph,
            final int[] labels,
            final int width,
            final int height,
            final PolygonShape domain,
            final double maxSpacing,
            final double minSpacing) {
        final BoundaryDistanceField field = BoundaryDistanceField.of(graph.snapshot(), width, height);
        final boolean[] material = domain.rasterize(width, height);
        final double minRadius = Math.max(minSpacing, MIN_RADIUS_RATIO * maxSpacing);
        final double maxRadius = MAX_RADIUS_RATIO * maxSpacing;
        final double layerHeight = Math.max(1.0, LAYER_HEIGHT_RATIO * minSpacing);
        final int layerCount = (int) Math.floor(field.peak() / layerHeight) + 1;
        final int[] offsets = new int[layerCount + 1];
        int total = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int cell = y * width + x;
                if (candidate(labels, material, cell)) {
                    // The shift makes the prefix sum the first index of every layer range.
                    offsets[layer(field.distance(x, y), layerHeight, layerCount) + 1]++;
                    total++;
                }
            }
        }
        for (int layer = 1; layer <= layerCount; layer++) {
            offsets[layer] += offsets[layer - 1];
        }
        final int[] order = new int[total];
        final int[] cursor = offsets.clone();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int cell = y * width + x;
                if (candidate(labels, material, cell)) {
                    order[cursor[layer(field.distance(x, y), layerHeight, layerCount)]++] = cell;
                }
            }
        }
        final Sampling sampling = new Sampling(graph, field, width, minRadius, maxRadius, maxSpacing, minSpacing);
        final Random shuffle = new Random(SHUFFLE_SEED);
        for (int layer = layerCount - 1; layer >= 0; layer--) {
            shuffleRegion(order, offsets[layer], offsets[layer + 1], shuffle);
        }
        for (int layer = layerCount - 1; layer >= 0; layer--) {
            sampling.placeRidges(order, offsets[layer], offsets[layer + 1]);
        }
        for (int layer = layerCount - 1; layer >= 0; layer--) {
            sampling.placeLayered(order, offsets[layer], offsets[layer + 1]);
        }
    }

    private static boolean candidate(final int[] labels, final boolean[] material, final int cell) {
        return labels[cell] != 0 && material[cell];
    }

    private static int layer(final double distance, final double layerHeight, final int layerCount) {
        return Math.min(layerCount - 1, Math.max(0, (int) Math.floor(distance / layerHeight)));
    }

    /** Deterministic Fisher-Yates shuffle of one distance layer. */
    private static void shuffleRegion(final int[] order, final int from, final int to, final Random shuffle) {
        for (int i = to - 1; i > from; i--) {
            final int pick = from + shuffle.nextInt(i - from + 1);
            final int swap = order[i];
            order[i] = order[pick];
            order[pick] = swap;
        }
    }

    /** Distance-graded disk sampling with its accepted-site index. */
    private static final class Sampling {

        private final SiteGraph graph;
        private final BoundaryDistanceField field;
        private final int width;
        private final int height;
        private final double minRadius;
        private final double maxRadius;
        private final double maxSpacing;
        private final double minSpacing;
        private final Accepted accepted;

        Sampling(
                final SiteGraph graph,
                final BoundaryDistanceField field,
                final int width,
                final double minRadius,
                final double maxRadius,
                final double maxSpacing,
                final double minSpacing) {
            this.graph = graph;
            this.field = field;
            this.width = width;
            this.height = field.height();
            this.minRadius = minRadius;
            this.maxRadius = maxRadius;
            this.maxSpacing = maxSpacing;
            this.minSpacing = minSpacing;
            this.accepted = new Accepted(maxRadius, width, height);
        }

        /** Accepts the medial-axis cells of one layer, which lie inside a narrow corridor. */
        void placeRidges(final int[] order, final int from, final int to) {
            for (int i = from; i < to; i++) {
                final int cell = order[i];
                if (!isRidge(cell % width, cell / width)) {
                    continue;
                }
                attempt(cell, i);
            }
        }

        /** Accepts the remaining cells of one layer, from the deepest to the shallowest. */
        void placeLayered(final int[] order, final int from, final int to) {
            for (int i = from; i < to; i++) {
                attempt(order[i], i);
            }
        }

        private void attempt(final int cell, final int rank) {
            final int x = cell % width;
            final int y = cell / width;
            final double distance = field.distance(x, y);
            final double spacing = Math.max(field.spacing(x, y), minSpacing);
            final double radius = radius(distance, spacing);
            if (distance < WALL_CLEARANCE_RATIO * Math.min(radius, spacing)) {
                return;
            }
            final double jitterX = jitter(rank) * JITTER_PX;
            final double jitterY = jitter(rank + 1) * JITTER_PX;
            final double siteX = x + jitterX;
            final double siteY = y + jitterY;
            if (!accepted.admits(siteX, siteY, radius, minSpacing)) {
                return;
            }
            accepted.add(siteX, siteY, radius);
            graph.site(siteX, siteY);
        }

        /**
         * Inhibition radius of one cell.
         *
         * <p>The radius starts at the border value and grows with the distance field until it reaches
         * its cap, which makes the sampling dense next to the silhouette and sparse in the open
         * middle.</p>
         */
        private double radius(final double distance, final double spacing) {
            final double edge =
                    Math.min(EDGE_RADIUS_CAP_RATIO * maxSpacing, Math.max(minRadius, EDGE_RADIUS_RATIO * spacing));
            final double graded = edge + GRADE_FACTOR * Math.max(0.0, distance - edge);
            return Math.min(maxRadius, Math.max(minRadius, graded));
        }

        /** True when no neighbour of the cell reaches further into the material. */
        private boolean isRidge(final int x, final int y) {
            final double centre = field.distance(x, y);
            boolean strict = false;
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    if (offsetX == 0 && offsetY == 0) {
                        continue;
                    }
                    final int neighbourX = x + offsetX;
                    final int neighbourY = y + offsetY;
                    if (neighbourX < 0 || neighbourY < 0 || neighbourX >= width || neighbourY >= height) {
                        continue;
                    }
                    final double neighbour = field.distance(neighbourX, neighbourY);
                    if (neighbour > centre + RIDGE_TOLERANCE_PX) {
                        return false;
                    }
                    if (neighbour < centre - RIDGE_TOLERANCE_PX) {
                        strict = true;
                    }
                }
            }
            return strict;
        }

        private double jitter(final int rank) {
            final double value = (rank + 1) * GOLDEN_RATIO_CONJUGATE;
            return (value - Math.floor(value)) * 2.0 - 1.0;
        }
    }

    /** Hash index of accepted sites, so each candidate only tests its own neighbourhood. */
    private static final class Accepted {

        private static final int INITIAL_CAPACITY = 256;

        private final double cellSize;
        private final int columns;
        private final int rows;
        private final int[] head;
        private double[] xs = new double[INITIAL_CAPACITY];
        private double[] ys = new double[INITIAL_CAPACITY];
        private double[] radii = new double[INITIAL_CAPACITY];
        private int[] next = new int[INITIAL_CAPACITY];
        private int count;

        Accepted(final double cellSize, final int width, final int height) {
            this.cellSize = cellSize;
            this.columns = (int) Math.ceil(width / cellSize) + 1;
            this.rows = (int) Math.ceil(height / cellSize) + 1;
            this.head = new int[columns * rows];
            Arrays.fill(head, -1);
        }

        /** True when the site keeps the separation contract against every accepted neighbour. */
        boolean admits(final double x, final double y, final double radius, final double minSpacing) {
            final int column = column(x);
            final int row = row(y);
            for (int offsetY = -1; offsetY <= 1; offsetY++) {
                for (int offsetX = -1; offsetX <= 1; offsetX++) {
                    final int probeColumn = column + offsetX;
                    final int probeRow = row + offsetY;
                    if (probeColumn < 0 || probeRow < 0 || probeColumn >= columns || probeRow >= rows) {
                        continue;
                    }
                    for (int site = head[probeRow * columns + probeColumn]; site >= 0; site = next[site]) {
                        final double separation = Math.max(minSpacing, PAIR_SEPARATION_RATIO * (radius + radii[site]));
                        if (Math.hypot(xs[site] - x, ys[site] - y) < separation) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        void add(final double x, final double y, final double radius) {
            if (count == xs.length) {
                final int capacity = count * 2;
                xs = Arrays.copyOf(xs, capacity);
                ys = Arrays.copyOf(ys, capacity);
                radii = Arrays.copyOf(radii, capacity);
                next = Arrays.copyOf(next, capacity);
            }
            final int bucket = row(y) * columns + column(x);
            next[count] = head[bucket];
            head[bucket] = count;
            xs[count] = x;
            ys[count] = y;
            radii[count] = radius;
            count++;
        }

        private int column(final double x) {
            return Math.min(columns - 1, Math.max(0, (int) Math.floor(x / cellSize)));
        }

        private int row(final double y) {
            return Math.min(rows - 1, Math.max(0, (int) Math.floor(y / cellSize)));
        }
    }
}
