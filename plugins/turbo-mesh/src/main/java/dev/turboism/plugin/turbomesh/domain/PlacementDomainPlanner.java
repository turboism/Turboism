package dev.turboism.plugin.turbomesh.domain;

import dev.turboism.plugin.turbomesh.domain.features.FeatureOptions;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.TexturePixels;
import java.util.function.Consumer;

/**
 * Selects the planning domain of one adaptive re-placement run and plans the mesh in it.
 *
 * <p>The adaptive planner accepts either a geometry-only domain or an alpha mask derived from the
 * texture pixels. Both entry points — the ArtMesh context menu and the auto-mesh algorithm — must
 * make that choice identically, otherwise two runs on the same input would produce different
 * meshes. This type owns the decision and reports which domain it used through a diagnostics sink,
 * so callers keep their own logging channel.</p>
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
