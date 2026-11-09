package dev.turboism.plugin.turbomesh.domain;

import dev.turboism.plugin.turbomesh.domain.features.DensityPreset;
import dev.turboism.plugin.turbomesh.domain.features.FeatureOptions;
import dev.turboism.plugin.turbomesh.domain.regions.ConstraintRole;
import dev.turboism.plugin.turbomesh.domain.regions.RegionPlacementParams;
import dev.turboism.plugin.turbomesh.domain.regions.RegionPlacementPlan;
import dev.turboism.plugin.turbomesh.domain.regions.RegionPlacementPlanner;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.TexturePixels;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Selects the planning domain of one adaptive re-placement run and plans the mesh in it.
 *
 * <p>The adaptive planner accepts either a geometry-only domain or an alpha mask derived from the
 * texture pixels. Both entry points — the ArtMesh context menu and the auto-mesh algorithm — must
 * make that choice identically, otherwise two runs on the same input would produce different
 * meshes. This type owns the decision and reports which domain it used through a diagnostics sink,
 * so callers keep their own logging channel.</p>
 *
 * <p>A texture run starts on the segmented regions of the artwork, because region borders carry
 * the drawn creases into the mesh. The raster holds the whole atlas page, so a region plan may
 * describe art of another mesh; such a plan leaves the source uv footprint and the run continues
 * on the alpha mask instead.</p>
 */
public final class PlacementDomainPlanner {

    /** System property that selects the texture alpha threshold. */
    public static final String ALPHA_THRESHOLD_PROPERTY = "turboMesh.alphaThreshold";

    /** Alpha threshold used when the host or the user states none. */
    public static final int DEFAULT_ALPHA_THRESHOLD = 8;

    /** Lowest alpha that still separates solid pixels from transparent ones. */
    private static final int MIN_ALPHA_THRESHOLD = 1;

    /** Highest alpha an 8-bit channel can carry. */
    private static final int MAX_ALPHA_THRESHOLD = 255;

    /** Uv distance beyond which a region plan no longer fits the source footprint. */
    static final double UV_FOOTPRINT_TOLERANCE = 0.15;

    /** Smallest pixel spacing that a projected uv knob may request. */
    private static final double MIN_PROJECTED_SPACING_PX = 1.0;

    /** Largest share of the maximum spacing that the minimum spacing may take. */
    private static final double MAX_MIN_SPACING_RATIO = 0.5;

    /** Preset name that the statistics of a region run report. */
    private static final String REGION_PRESET_NAME = "texture-regions";

    private PlacementDomainPlanner() {}

    /**
     * Plans one re-placement, preferring the texture alpha domain when pixels are available.
     *
     * @param source the mesh whose boundary rings and uv footprint drive the run
     * @param texture the texture input, or {@code null} when the host exposes no pixels
     * @param settings spacing and segmentation knobs
     * @param options feature weights for the 0.2.0 placement path
     * @param diagnostics receives one line describing the domain that the run used
     * @return the complete replacement geometry
     * @throws TurboMeshPlanner.PlacementException when no mesh can be planned
     */
    public static TurboMeshPlanner.PlacementPlan plan(
            final ArtMeshGeometry source,
            final TexturePixels texture,
            final PlacementSettings settings,
            final FeatureOptions options,
            final Consumer<String> diagnostics)
            throws TurboMeshPlanner.PlacementException {
        if (texture == null) {
            diagnostics.accept("turbo-mesh planning domain: no texture pixels, geometry fallback");
            return TurboMeshPlanner.plan(source, settings, options);
        }
        final TurboMeshPlanner.PlacementPlan regions = regionPlan(source, texture, settings, options, diagnostics);
        if (regions != null) {
            diagnostics.accept("turbo-mesh planning domain: texture regions");
            return regions;
        }
        try {
            final DomainMask mask = textureMask(source, texture, settings);
            diagnostics.accept("turbo-mesh planning domain: texture alpha mask");
            return TurboMeshPlanner.plan(source, mask, settings, options);
        } catch (IllegalArgumentException | IllegalStateException invalid) {
            diagnostics.accept(
                    "turbo-mesh planning domain: texture mask rejected (" + invalid + "), geometry fallback");
        }
        return TurboMeshPlanner.plan(source, settings, options);
    }

    /**
     * Plans one run on the segmented regions of the texture page.
     *
     * @return the plan, or {@code null} when the planner rejected the texture, the plan left the
     *     source uv footprint, or the plan exceeds the point cap
     */
    private static TurboMeshPlanner.PlacementPlan regionPlan(
            final ArtMeshGeometry source,
            final TexturePixels texture,
            final PlacementSettings settings,
            final FeatureOptions options,
            final Consumer<String> diagnostics) {
        final TextureUvMapping pixelToUv = TextureUvMapping.of(texture.width(), texture.height(), texture.uvToPixel());
        final RegionPlacementPlan regions;
        try {
            regions = RegionPlacementPlanner.plan(
                    texture.argb(),
                    texture.width(),
                    texture.height(),
                    mappedParams(settings, options, pixelToUv, footprintSpanPx(source, pixelToUv)));
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            diagnostics.accept(
                    "turbo-mesh planning domain: texture regions rejected (" + rejected + "), alpha-mask fallback");
            return null;
        }
        if (regions.vertices().size() > settings.maxPoints()) {
            diagnostics.accept("turbo-mesh planning domain: texture regions exceed the point cap, alpha-mask fallback");
            return null;
        }
        final List<Point2> uvs = new ArrayList<>(regions.vertices().size());
        for (final Point2 vertex : regions.vertices()) {
            uvs.add(pixelToUv.map(vertex.x(), vertex.y()));
        }
        if (!fitsFootprint(source.uvs(), uvs)) {
            diagnostics.accept(
                    "turbo-mesh planning domain: texture regions left the uv footprint, alpha-mask fallback");
            return null;
        }
        final UvToPosition uvToPosition = UvToPosition.of(source.uvs(), source.positions(), source.triangleIndices());
        final List<Point2> positions = new ArrayList<>(uvs.size());
        for (final Point2 uv : uvs) {
            positions.add(uvToPosition.map(uv.x(), uv.y()));
        }
        final List<Integer> triangles = new ArrayList<>(regions.triangleIndices().length);
        for (final int index : regions.triangleIndices()) {
            triangles.add(index);
        }
        return new TurboMeshPlanner.PlacementPlan(new ArtMeshGeometry(positions, uvs, triangles), regionStats(regions));
    }

    /**
     * Projects the uv-space settings onto the raster knobs of the region planner.
     *
     * <p>The region planner owns the reviewed pixel scale of a texture; the uv knobs only refine
     * it. Each projected value therefore keeps the tighter of the two, so a coarse preset cannot
     * collapse the silhouette to a handful of boundary points.</p>
     *
     * <p>Region size and contrast stay automatic: the artwork decides them. The density preset only
     * selects the region level.</p>
     *
     * @param settings uv-space spacing knobs of this run
     * @param options density preset of this run, which selects the region level
     * @param pixelToUv the inverse host affine, used for its uv-to-pixel scale
     * @param spanPx longest extent of the source uv footprint in pixels
     */
    private static RegionPlacementParams mappedParams(
            final PlacementSettings settings,
            final FeatureOptions options,
            final TextureUvMapping pixelToUv,
            final double spanPx) {
        final double pixelsPerUv = pixelToUv.pixelsPerUv();
        final int level = regionLevel(options.preset());
        final RegionPlacementParams.Resolved reference = new RegionPlacementParams(
                        null, null, level, RegionPlacementParams.DEFAULT_TRACK_COUNT, null, null, null)
                .resolve(spanPx);
        final double maxSpacing = Math.max(
                MIN_PROJECTED_SPACING_PX,
                Math.min(settings.maxBoundarySpacingUv() * pixelsPerUv, reference.maxSpacing()));
        final double minSpacing = Math.min(
                Math.max(settings.minSpacingUv() * pixelsPerUv, reference.minSpacing()),
                MAX_MIN_SPACING_RATIO * maxSpacing);
        final double tolerance = Math.min(settings.sagittaUv() * pixelsPerUv, reference.simplifyTolerance());
        return new RegionPlacementParams(
                null, null, level, RegionPlacementParams.DEFAULT_TRACK_COUNT, maxSpacing, tolerance, minSpacing);
    }

    /**
     * Region level of a density preset.
     *
     * <p>Strands and blended soft geometry need the medium level, because a coarser one merges
     * neighbouring strands. Broad sheets and rigid plates merge shading first, which keeps their
     * region borders on the real creases only.</p>
     */
    private static int regionLevel(final DensityPreset preset) {
        return switch (preset) {
            case AUTO_BLEND, SOFT_STRIP -> 2;
            case SOFT_SHEET, RIGID_GEOMETRY -> RegionPlacementParams.DEFAULT_LEVEL;
        };
    }

    /**
     * Longest extent of the source uv footprint in raster pixels.
     *
     * @throws IllegalArgumentException when the footprint has no extent, which no plan can serve
     */
    private static double footprintSpanPx(final ArtMeshGeometry source, final TextureUvMapping pixelToUv) {
        final double[] bounds = uvBounds(source.uvs());
        final double span = Math.max(bounds[2] - bounds[0], bounds[3] - bounds[1]) * pixelToUv.pixelsPerUv();
        if (!(span > 0.0) || !Double.isFinite(span)) {
            throw new IllegalArgumentException("source mesh uv footprint has no extent");
        }
        return span;
    }

    /** True when every vertex stays within {@link #UV_FOOTPRINT_TOLERANCE} of the source uv bounds. */
    private static boolean fitsFootprint(final List<Point2> sourceUvs, final List<Point2> planUvs) {
        final double[] bounds = uvBounds(sourceUvs);
        for (final Point2 uv : planUvs) {
            if (uv.x() < bounds[0] - UV_FOOTPRINT_TOLERANCE
                    || uv.x() > bounds[2] + UV_FOOTPRINT_TOLERANCE
                    || uv.y() < bounds[1] - UV_FOOTPRINT_TOLERANCE
                    || uv.y() > bounds[3] + UV_FOOTPRINT_TOLERANCE) {
                return false;
            }
        }
        return true;
    }

    /** Uv bounds of a vertex list as {@code [minU, minV, maxU, maxV]}. */
    private static double[] uvBounds(final List<Point2> uvs) {
        double minU = Double.MAX_VALUE;
        double minV = Double.MAX_VALUE;
        double maxU = -Double.MAX_VALUE;
        double maxV = -Double.MAX_VALUE;
        for (final Point2 uv : uvs) {
            minU = Math.min(minU, uv.x());
            minV = Math.min(minV, uv.y());
            maxU = Math.max(maxU, uv.x());
            maxV = Math.max(maxV, uv.y());
        }
        return new double[] {minU, minV, maxU, maxV};
    }

    /** Statistics of a region run; outline vertices count as boundary and the rest as interior. */
    private static TurboMeshPlanner.Stats regionStats(final RegionPlacementPlan regions) {
        final boolean[] boundary = new boolean[regions.vertices().size()];
        int boundaryCount = 0;
        for (final int[] ring : regions.domainRings()) {
            for (final int vertex : ring) {
                if (!boundary[vertex]) {
                    boundary[vertex] = true;
                    boundaryCount++;
                }
            }
        }
        int separators = 0;
        for (final RegionPlacementPlan.Constraint constraint : regions.constraints()) {
            if (constraint.role() != ConstraintRole.OUTLINE) {
                separators++;
            }
        }
        return new TurboMeshPlanner.Stats(
                regions.regions().regionCount(),
                boundaryCount,
                regions.vertices().size() - boundaryCount,
                separators,
                regions.triangleCount(),
                REGION_PRESET_NAME);
    }

    /**
     * Reads the alpha threshold that separates solid texture pixels.
     *
     * @return the threshold clamped into the 8-bit alpha range, or the default when unset
     */
    public static int alphaThreshold() {
        try {
            return Math.max(
                    MIN_ALPHA_THRESHOLD,
                    Math.min(
                            MAX_ALPHA_THRESHOLD,
                            Integer.parseInt(System.getProperty(ALPHA_THRESHOLD_PROPERTY, "")
                                    .trim())));
        } catch (RuntimeException ignored) {
            return DEFAULT_ALPHA_THRESHOLD;
        }
    }

    /**
     * Rasterizes the texture alpha mask over the padded uv footprint of the source mesh.
     *
     * <p>The padding keeps boundary samples inside the sampled rectangle, because the mask only
     * answers "solid or transparent" inside the extent it was built from.</p>
     */
    private static DomainMask textureMask(
            final ArtMeshGeometry source, final TexturePixels texture, final PlacementSettings settings) {
        double u0 = Double.MAX_VALUE;
        double v0 = Double.MAX_VALUE;
        double u1 = -Double.MAX_VALUE;
        double v1 = -Double.MAX_VALUE;
        for (final Point2 uv : source.uvs()) {
            u0 = Math.min(u0, uv.x());
            v0 = Math.min(v0, uv.y());
            u1 = Math.max(u1, uv.x());
            v1 = Math.max(v1, uv.y());
        }
        final double pad = settings.interiorMarginUv() + settings.maxBoundarySpacingUv();
        return DomainMask.fromPixels(
                texture.argb(),
                texture.width(),
                texture.height(),
                texture.uvToPixel(),
                u0 - pad,
                v0 - pad,
                u1 + pad,
                v1 + pad,
                settings.rasterMaxDim(),
                alphaThreshold());
    }
}
