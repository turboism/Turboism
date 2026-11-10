package dev.turboism.plugin.turbomesh.domain;

/**
 * Tunables for one ArtMesh re-placement run. All lengths are in UV units
 * ({@code 1.0} spans the texture) unless noted otherwise.
 *
 * <p>Density is geometry-driven, not budget-driven: the boundary earns points only where the
 * outline curves (chord-error bound) or where an edge is long enough to need deformation
 * support (arc cap), and the interior is filled on a fixed sparse grid because without texture
 * pixels there is no signal that would justify more.</p>
 *
 * @param maxBoundarySpacingUv maximum polyline arc length between two consecutive boundary
 *     samples — straight edges gain at most ceil(len/cap) points for deformation support
 * @param interiorSpacingUv interior jittered-grid spacing; without texture signal this stays
 *     coarse on purpose
 * @param minSpacingUv hard lower bound on the distance between any two placed points
 * @param interiorMarginUv minimum distance transform value (UV units) an interior point must
 *     keep from its region boundary — the protection ring
 * @param sagittaUv maximum chord deviation allowed when resampling a boundary ring — this is
 *     the knob that makes curvature decide the point count
 * @param collinearTurnRadians turn angle below which an original boundary vertex is treated as
 *     non-structural and skipped (the sagitta pass may still reinsert it)
 * @param tipTurnRadians turn angle above which a boundary vertex is treated as a tip anchor
 * @param splitAspect major/minor bounding-axis ratio that triggers a recursive region split
 * @param minRegionCells region area in mask cells below which recursion stops
 * @param maxDepth recursion depth cap for region splitting
 * @param rasterMaxDim largest side of the UV rasterization grid in cells
 * @param maxPoints absolute output vertex cap; runs exceeding it fail instead of writing
 * @param seed deterministic jitter seed for interior sampling
 * @param edgeMinContrast minimum 0..1 luminance contrast for a texture ridge cell to count as
 *     line art; also scales the region-boundary luma threshold
 * @param edgeColorDistance minimum normalized RGB distance (0..1.73) across a cell pair that
 *     marks a colour-region boundary
 * @param edgeMinLengthUv traced edge chains shorter than this are dropped as noise
 * @param edgeDpToleranceUv Douglas–Peucker tolerance for traced chains, in UV units
 * @param edgeStitchCells maximum gap, in mask cells, bridged when stitching two dead-end
 *     chain tips that continue each other's direction; 0 disables stitching
 */
public record PlacementSettings(
        double maxBoundarySpacingUv,
        double interiorSpacingUv,
        double minSpacingUv,
        double interiorMarginUv,
        double sagittaUv,
        double collinearTurnRadians,
        double tipTurnRadians,
        double splitAspect,
        int minRegionCells,
        int maxDepth,
        int rasterMaxDim,
        int maxPoints,
        long seed,
        double edgeMinContrast,
        double edgeColorDistance,
        double edgeMinLengthUv,
        double edgeDpToleranceUv,
        int edgeStitchCells) {

    /** Default ridge contrast (0..1 luminance units). */
    public static final double DEFAULT_EDGE_MIN_CONTRAST = 0.12;
    /** Default colour-boundary distance, normalized RGB distance units. */
    public static final double DEFAULT_EDGE_COLOR_DISTANCE = 0.35;
    /** Default minimum traced chain length. */
    public static final double DEFAULT_EDGE_MIN_LENGTH_UV = 0.012;
    /** Default Douglas–Peucker tolerance, roughly one raster cell. */
    public static final double DEFAULT_EDGE_DP_TOLERANCE_UV = 0.0035;
    /** Default gap radius (mask cells) for end-to-end stitching of fragmented chains. */
    public static final int DEFAULT_EDGE_STITCH_CELLS = 6;

    public PlacementSettings {
        if (!(maxBoundarySpacingUv > 0.0) || !(interiorSpacingUv > 0.0)) {
            throw new IllegalArgumentException("spacing settings must be positive");
        }
        if (!(minSpacingUv > 0.0) || !(interiorMarginUv >= 0.0)) {
            throw new IllegalArgumentException("spacing and margin must be finite and non-negative");
        }
        if (!(sagittaUv > 0.0) || !(collinearTurnRadians > 0.0) || !(tipTurnRadians > 0.0)) {
            throw new IllegalArgumentException("curvature settings must be finite and positive");
        }
        if (collinearTurnRadians >= tipTurnRadians) {
            throw new IllegalArgumentException("collinear threshold must be below the tip threshold");
        }
        if (!(splitAspect > 1.0) || minRegionCells < 1 || maxDepth < 0 || rasterMaxDim < 8) {
            throw new IllegalArgumentException("segmentation settings are out of range");
        }
        if (maxPoints < 8) {
            throw new IllegalArgumentException("maxPoints must cover a minimal mesh");
        }
        if (!(edgeMinContrast > 0.0)
                || !(edgeColorDistance > 0.0)
                || !(edgeMinLengthUv >= 0.0)
                || !(edgeDpToleranceUv >= 0.0)
                || edgeStitchCells < 0) {
            throw new IllegalArgumentException("edge settings must be finite and non-negative");
        }
    }

    /** Sensible defaults for a sparse, curvature-driven re-placement. */
    public static PlacementSettings defaults() {
        return new PlacementSettings(
                0.12,
                0.20,
                0.004,
                0.006,
                0.0025,
                Math.toRadians(3.0),
                Math.toRadians(55.0),
                3.0,
                48,
                6,
                256,
                4096,
                0x5DEE5D5DL,
                DEFAULT_EDGE_MIN_CONTRAST,
                DEFAULT_EDGE_COLOR_DISTANCE,
                DEFAULT_EDGE_MIN_LENGTH_UV,
                DEFAULT_EDGE_DP_TOLERANCE_UV,
                DEFAULT_EDGE_STITCH_CELLS);
    }

    /**
     * Copy of these settings with the four user-facing density knobs replaced.
     *
     * <p>The adaptive algorithm exposes only these fields as parameters; every structural bound
     * keeps its reviewed value, so a stored preset cannot loosen one.</p>
     *
     * @param maxBoundarySpacingUv maximum arc length between two boundary samples
     * @param interiorSpacingUv interior fill pitch
     * @param minSpacingUv hard lower bound between two placed points
     * @param maxPoints absolute output vertex cap
     * @return the adjusted settings
     */
    public PlacementSettings withDensity(
            final double maxBoundarySpacingUv,
            final double interiorSpacingUv,
            final double minSpacingUv,
            final int maxPoints) {
        return new PlacementSettings(
                maxBoundarySpacingUv,
                interiorSpacingUv,
                minSpacingUv,
                interiorMarginUv,
                sagittaUv,
                collinearTurnRadians,
                tipTurnRadians,
                splitAspect,
                minRegionCells,
                maxDepth,
                rasterMaxDim,
                maxPoints,
                seed,
                edgeMinContrast,
                edgeColorDistance,
                edgeMinLengthUv,
                edgeDpToleranceUv,
                edgeStitchCells);
    }

    /** Reads an int override from {@code System.getProperty}, clamped to the given range. */
    static int intProperty(final String name, final int fallback, final int min, final int max) {
        try {
            final int value = Integer.parseInt(System.getProperty(name, "").trim());
            return Math.max(min, Math.min(max, value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /** Reads a double override from {@code System.getProperty}, clamped to the given range. */
    static double doubleProperty(final String name, final double fallback, final double min, final double max) {
        try {
            final double value = Double.parseDouble(System.getProperty(name, "").trim());
            return Math.max(min, Math.min(max, value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    /** System-property overrides kept for bring-up; the settings dialog lands separately. */
    public static PlacementSettings fromSystemProperties() {
        final PlacementSettings base = defaults();
        return new PlacementSettings(
                doubleProperty("turboMesh.maxBoundarySpacingUv", base.maxBoundarySpacingUv, base.minSpacingUv, 0.5),
                doubleProperty("turboMesh.interiorSpacingUv", base.interiorSpacingUv, base.minSpacingUv, 1.0),
                base.minSpacingUv,
                base.interiorMarginUv,
                base.sagittaUv,
                base.collinearTurnRadians,
                base.tipTurnRadians,
                base.splitAspect,
                base.minRegionCells,
                base.maxDepth,
                base.rasterMaxDim,
                intProperty("turboMesh.maxPoints", base.maxPoints(), 64, 65536),
                base.seed(),
                doubleProperty("turboMesh.edgeMinContrast", base.edgeMinContrast(), 0.01, 1.0),
                doubleProperty("turboMesh.edgeColorDistance", base.edgeColorDistance(), 0.01, 1.8),
                doubleProperty("turboMesh.edgeMinLengthUv", base.edgeMinLengthUv(), 0.0, 0.5),
                doubleProperty("turboMesh.edgeDpToleranceUv", base.edgeDpToleranceUv(), 0.0, 0.1),
                intProperty("turboMesh.edgeStitchCells", base.edgeStitchCells(), 0, 64));
    }
}
