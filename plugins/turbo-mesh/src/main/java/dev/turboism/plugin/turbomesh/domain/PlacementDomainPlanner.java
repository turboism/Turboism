package dev.turboism.plugin.turbomesh.domain;

import dev.turboism.plugin.turbomesh.domain.features.FeatureOptions;
import dev.turboism.plugin.turbomesh.domain.regions.SilhouetteOutline;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.TexturePixels;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Selects the planning domain of one adaptive re-placement run and plans the mesh in it.
 *
 * <p>The adaptive planner prefers a bleed-expanded artwork silhouette paired with the feature-aware
 * adaptive pipeline ({@link TurboMeshPlanner#planWithRings}). When the texture page holds multiple
 * parts outside the source UV footprint, or extraction fails, the run falls back to the padded
 * alpha mask or pure source geometry.</p>
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

    /** Uv distance beyond which a silhouette plan no longer fits the source footprint. */
    static final double UV_FOOTPRINT_TOLERANCE = 0.15;

    /** Smallest pixel spacing that a projected uv knob may request. */
    private static final double MIN_PROJECTED_SPACING_PX = 1.0;

    /** Smallest curvature tolerance in pixels when extracting the adaptive silhouette outline. */
    private static final double MIN_SILHOUETTE_TOLERANCE_PX = 0.6;

    private PlacementDomainPlanner() {}

    /**
     * Plans one re-placement, preferring the bleed-expanded texture silhouette and feature pipeline.
     *
     * @param source the mesh whose boundary rings and uv footprint drive the run
     * @param texture the texture input, or {@code null} when the host exposes no pixels
     * @param settings spacing, bleed, and segmentation knobs
     * @param options feature weights for the adaptive placement path
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
        final TurboMeshPlanner.PlacementPlan adaptive =
                adaptiveSilhouettePlan(source, texture, settings, options, diagnostics);
        if (adaptive != null) {
            diagnostics.accept("turbo-mesh planning domain: adaptive silhouette + texture features");
            return adaptive;
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
     * Extracts bleed-expanded silhouette rings from texture pixels and plans with feature fields.
     *
     * @return the plan, or {@code null} when extraction fails or leaves the source uv footprint
     */
    private static TurboMeshPlanner.PlacementPlan adaptiveSilhouettePlan(
            final ArtMeshGeometry source,
            final TexturePixels texture,
            final PlacementSettings settings,
            final FeatureOptions options,
            final Consumer<String> diagnostics) {
        final TextureUvMapping pixelToUv = TextureUvMapping.of(texture.width(), texture.height(), texture.uvToPixel());
        final double pixelsPerUv = pixelToUv.pixelsPerUv();
        final double maxSpacingPx = Math.max(MIN_PROJECTED_SPACING_PX, settings.maxBoundarySpacingUv() * pixelsPerUv);
        final double tolerancePx = Math.max(MIN_SILHOUETTE_TOLERANCE_PX, settings.sagittaUv() * pixelsPerUv);
        final double minSpacingPx = Math.max(MIN_PROJECTED_SPACING_PX, settings.minSpacingUv() * pixelsPerUv);
        final SilhouetteOutline.Result outline;
        try {
            outline = SilhouetteOutline.extract(
                    texture.argb(),
                    texture.width(),
                    texture.height(),
                    settings.bleedMarginPx(),
                    maxSpacingPx,
                    tolerancePx,
                    minSpacingPx);
        } catch (IllegalArgumentException | IllegalStateException rejected) {
            diagnostics.accept(
                    "turbo-mesh planning domain: adaptive silhouette rejected (" + rejected + "), alpha-mask fallback");
            return null;
        }
        if (outline == null) {
            diagnostics.accept("turbo-mesh planning domain: adaptive silhouette closed empty, alpha-mask fallback");
            return null;
        }
        final List<List<Point2>> uvRings = new ArrayList<>(outline.rings().size());
        final List<Point2> allOutlineUvs = new ArrayList<>();
        for (final List<Point2> pixelRing : outline.rings()) {
            final List<Point2> uvRing = new ArrayList<>(pixelRing.size());
            for (final Point2 pixel : pixelRing) {
                final Point2 uv = pixelToUv.map(pixel.x(), pixel.y());
                uvRing.add(uv);
                allOutlineUvs.add(uv);
            }
            uvRings.add(List.copyOf(uvRing));
        }
        if (!fitsFootprint(source.uvs(), allOutlineUvs)) {
            diagnostics.accept(
                    "turbo-mesh planning domain: adaptive silhouette left the uv footprint, alpha-mask fallback");
            return null;
        }
        final double[] bounds = uvBounds(allOutlineUvs);
        final double pad = settings.interiorMarginUv() + settings.maxBoundarySpacingUv();
        final DomainMask mask = DomainMask.fromPixelsWithRings(
                texture.argb(),
                texture.width(),
                texture.height(),
                texture.uvToPixel(),
                bounds[0] - pad,
                bounds[1] - pad,
                bounds[2] + pad,
                bounds[3] + pad,
                settings.rasterMaxDim(),
                uvRings);
        try {
            return TurboMeshPlanner.planWithRings(source, mask, uvRings, settings, options);
        } catch (TurboMeshPlanner.PlacementException | IllegalArgumentException | IllegalStateException failure) {
            diagnostics.accept(
                    "turbo-mesh planning domain: adaptive silhouette rejected (" + failure + "), alpha-mask fallback");
            return null;
        }
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
