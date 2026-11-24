package dev.turboism.plugin.turbomesh.domain.regions;

/**
 * Placement knobs for {@link RegionPlacementPlanner}.
 *
 * <p>Optional values accept {@code null} and then inherit silhouette-scaled defaults derived from
 * the longest solid bounding-box extent. This type carries no resolved pixels, so callers can
 * reuse one instance for images of different sizes.</p>
 */
public record RegionPlacementParams(
        Double minRegionRatio,
        Double contrast,
        int level,
        Double bleedMargin,
        Double maxSpacing,
        Double simplifyTolerance,
        Double minSpacing) {

    /** Fraction of the solid area below which a region merges into its closest neighbour. */
    public static final double DEFAULT_MIN_REGION_RATIO = 0.006;

    /**
     * Default contrast: CIELAB distance that separates regions when the caller states none.
     *
     * <p>The value stays on the 0.5 grid that the parameter descriptor declares, because a default
     * off that grid is rejected at declaration time. Automatic runs ignore it and derive the
     * contrast from the artwork gradient; it only seeds the manual control.</p>
     */
    public static final double DEFAULT_CONTRAST = 7.5;

    /** Medium region level used as default so primary texture divisions stay visible. */
    public static final int DEFAULT_LEVEL = 2;

    /** Default transparent bleed margin expanded outside the visible silhouette, in pixels. */
    public static final double DEFAULT_BLEED_MARGIN_PX = 6.0;

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

    /** Auto bleed margin as a fraction of the silhouette span. */
    static final double AUTO_BLEED_SPAN_RATIO = 0.012;

    /** Minimum automatic bleed margin in pixels. */
    static final double AUTO_BLEED_FLOOR_PX = 4.0;

    /** Maximum automatic bleed margin in pixels. */
    static final double AUTO_BLEED_CEILING_PX = 10.0;

    /** Reference silhouette span in pixels for scaling automatic minimum region share. */
    static final double AUTO_MIN_REGION_REF_SPAN_PX = 512.0;

    /** Lower bound of automatic minimum region share. */
    static final double AUTO_MIN_REGION_FLOOR = 0.0025;

    /** Upper bound of automatic minimum region share. */
    static final double AUTO_MIN_REGION_CEILING = 0.015;

    /**
     * Sparse preset: coarse mesh with automatic silhouette scaling.
     */
    public static final RegionPlacementParams SPARSE =
            new RegionPlacementParams(0.008, null, 1, null, null, null, null);

    /**
     * Detailed preset: preserves fine texture divisions and dense curvature sampling.
     */
    public static final RegionPlacementParams DETAILED =
            new RegionPlacementParams(0.003, null, 3, null, 24.0, 1.0, 3.5);

    /**
     * Hard-surface preset: clean geometric outline with moderate region separation.
     */
    public static final RegionPlacementParams HARD_SURFACE =
            new RegionPlacementParams(0.010, 6.5, 2, 5.0, 30.0, 1.0, 3.5);

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
        requireNonNegative("bleedMargin", bleedMargin);
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
        final double resolvedMinRatio = minRegionRatio == null
                ? Math.max(
                        AUTO_MIN_REGION_FLOOR,
                        Math.min(
                                AUTO_MIN_REGION_CEILING,
                                DEFAULT_MIN_REGION_RATIO * (AUTO_MIN_REGION_REF_SPAN_PX / silhouetteSpan)))
                : minRegionRatio;
        final double resolvedBleed = bleedMargin == null
                ? Math.max(AUTO_BLEED_FLOOR_PX, Math.min(AUTO_BLEED_CEILING_PX, AUTO_BLEED_SPAN_RATIO * silhouetteSpan))
                : bleedMargin;
        return new Resolved(
                resolvedMinRatio,
                contrast == null ? DEFAULT_CONTRAST : contrast,
                level,
                resolvedBleed,
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
            double bleedMargin,
            double maxSpacing,
            double simplifyTolerance,
            double minSpacing) {}

    private static void requirePositive(final String name, final Double value) {
        if (value == null) {
            return;
        }
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException(name + " must be positive and finite but was " + value);
        }
    }

    private static void requireNonNegative(final String name, final Double value) {
        if (value == null) {
            return;
        }
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(name + " must be non-negative and finite but was " + value);
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
