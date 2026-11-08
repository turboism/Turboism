package dev.turboism.plugin.turbomesh.domain.regions;

/**
 * Placement knobs for {@link RegionPlacementPlanner}.
 *
 * <p>Optional values accept {@code null} and then inherit the silhouette-scaled defaults that the
 * offline prototype derived from the longest bounding-box extent. This type carries no resolved
 * pixels, so callers can reuse one instance for images of different sizes.</p>
 */
public record RegionPlacementParams(
        Double minRegionRatio,
        Double contrast,
        int level,
        int trackCount,
        Double maxSpacing,
        Double simplifyTolerance,
        Double minSpacing) {

    /** Fraction of the solid area below which a region merges into its closest neighbour. */
    public static final double DEFAULT_MIN_REGION_RATIO = 0.006;

    /** Base Lab colour distance for region merging; the three levels scale it. */
    public static final double DEFAULT_CONTRAST = 6.0;

    /** Coarsest region level, which the prototype used as its default. */
    public static final int DEFAULT_LEVEL = 1;

    /** Number of interior direction tracks per shape. */
    public static final int DEFAULT_TRACK_COUNT = 3;

    /** Upper bound of the direction track count. */
    public static final int MAX_TRACK_COUNT = 8;

    /** Number of nested region levels produced by the segmenter. */
    public static final int LEVEL_COUNT = 3;

    /** Auto spacing as a fraction of the silhouette span. */
    static final double AUTO_SPACING_SPAN_RATIO = 0.087;

    /** Auto simplification tolerance as a fraction of the silhouette span. */
    static final double AUTO_TOLERANCE_SPAN_RATIO = 0.00375;

    /** Auto simplification tolerance floor in pixels. */
    static final double AUTO_TOLERANCE_FLOOR_PX = 0.6;

    /** Auto near-point merge distance as a fraction of the silhouette span. */
    static final double AUTO_MIN_SPACING_SPAN_RATIO = 0.009;

    /** Auto near-point merge distance floor in pixels. */
    static final double AUTO_MIN_SPACING_FLOOR_PX = 1.5;

    /** Scale from boundary spacing to the interior fill pitch. */
    static final double INTERIOR_SPACING_SCALE = 1.4;

    /** Sparse preset: the prototype run whose output the notes record as hair-sparse. */
    public static final RegionPlacementParams SPARSE = new RegionPlacementParams(
            DEFAULT_MIN_REGION_RATIO, DEFAULT_CONTRAST, 1, DEFAULT_TRACK_COUNT, null, null, null);

    /** Detailed preset: the prototype run recorded as hair-detailed. */
    public static final RegionPlacementParams DETAILED = new RegionPlacementParams(
            DEFAULT_MIN_REGION_RATIO, DEFAULT_CONTRAST, 2, DEFAULT_TRACK_COUNT, 42.0, 2.0, 6.0);

    /** Hard-surface preset: the prototype run recorded as usb-sparse. */
    public static final RegionPlacementParams HARD_SURFACE =
            new RegionPlacementParams(0.015, 8.0, 1, DEFAULT_TRACK_COUNT, 32.0, 1.2, 4.0);

    /**
     * Rejects values the planner cannot honour.
     *
     * @throws IllegalArgumentException when a field is out of range, naming the offending field
     */
    public void validate() {
        requireInRange("minRegionRatio", minRegionRatio, 0.0, 1.0, false);
        requirePositive("contrast", contrast);
        if (level < 1 || level > LEVEL_COUNT) {
            throw new IllegalArgumentException("level must be within 1.." + LEVEL_COUNT + " but was " + level);
        }
        if (trackCount < 1 || trackCount > MAX_TRACK_COUNT) {
            throw new IllegalArgumentException(
                    "trackCount must be within 1.." + MAX_TRACK_COUNT + " but was " + trackCount);
        }
        requirePositive("maxSpacing", maxSpacing);
        requirePositive("simplifyTolerance", simplifyTolerance);
        requirePositive("minSpacing", minSpacing);
    }

    /**
     * Resolves every optional value against the silhouette span.
     *
     * @param silhouetteSpan longest bounding-box extent of the solid alpha area in pixels
     */
    public Resolved resolve(final double silhouetteSpan) {
        if (!(silhouetteSpan > 0.0) || !Double.isFinite(silhouetteSpan)) {
            throw new IllegalArgumentException("silhouetteSpan must be positive and finite but was " + silhouetteSpan);
        }
        return new Resolved(
                minRegionRatio == null ? DEFAULT_MIN_REGION_RATIO : minRegionRatio,
                contrast == null ? DEFAULT_CONTRAST : contrast,
                level,
                trackCount,
                maxSpacing == null ? AUTO_SPACING_SPAN_RATIO * silhouetteSpan : maxSpacing,
                simplifyTolerance == null
                        ? Math.max(AUTO_TOLERANCE_FLOOR_PX, AUTO_TOLERANCE_SPAN_RATIO * silhouetteSpan)
                        : simplifyTolerance,
                minSpacing == null
                        ? Math.max(AUTO_MIN_SPACING_FLOOR_PX, AUTO_MIN_SPACING_SPAN_RATIO * silhouetteSpan)
                        : minSpacing);
    }

    /** Params with every auto value replaced by a concrete pixel quantity. */
    public record Resolved(
            double minRegionRatio,
            double contrast,
            int level,
            int trackCount,
            double maxSpacing,
            double simplifyTolerance,
            double minSpacing) {

        /** Interior fill pitch; the hex lattice uses this pitch for its row and column steps. */
        public double interiorSpacing() {
            return INTERIOR_SPACING_SCALE * maxSpacing;
        }
    }

    private static void requirePositive(final String name, final Double value) {
        if (value == null) {
            return;
        }
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be positive and finite but was " + value);
        }
    }

    private static void requireInRange(
            final String name, final Double value, final double low, final double high, final boolean includeLow) {
        if (value == null) {
            return;
        }
        final boolean aboveLow = includeLow ? value >= low : value > low;
        if (!Double.isFinite(value) || !aboveLow || value > high) {
            throw new IllegalArgumentException(
                    name + " must be within " + (includeLow ? "[" : "(") + low + ", " + high + "] but was " + value);
        }
    }
}
