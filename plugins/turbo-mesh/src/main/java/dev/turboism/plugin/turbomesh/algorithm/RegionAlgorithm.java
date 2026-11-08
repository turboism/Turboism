package dev.turboism.plugin.turbomesh.algorithm;

import dev.turboism.plugin.turbomesh.domain.TextureUvMapping;
import dev.turboism.plugin.turbomesh.domain.UvToPosition;
import dev.turboism.plugin.turbomesh.domain.regions.RegionPlacementParams;
import dev.turboism.plugin.turbomesh.domain.regions.RegionPlacementPlan;
import dev.turboism.plugin.turbomesh.domain.regions.RegionPlacementPlanner;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshCompute;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshRequest;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.TexturePixels;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.CancellationToken;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/**
 * Texture-region placement published as an auto-mesh algorithm.
 *
 * <p>The Editor's Auto Mesh Generate entry runs this algorithm on the texture pixels of the
 * selected ArtMesh. Regions come from the artwork itself, so hair strands, garment folds and metal
 * parts each keep their own separator edges instead of one global point grid.</p>
 *
 * <p>Every nullable field of {@link RegionPlacementParams} carries a boolean companion parameter,
 * because a nullable "automatic" value has no representation in the parameter schema. The value
 * applies while its companion is {@code false}; while it is {@code true} the planner derives the
 * quantity from the silhouette span. The framework renders each control independently, so an
 * explicit companion states which number the run reads instead of hiding a sentinel value.</p>
 */
public final class RegionAlgorithm {

    /** Stable algorithm id; the runtime keys the session selection by it. */
    public static final String ID = "turbo-mesh.region";

    /** Reason code reported when the request carries no texture pixels. */
    public static final String REASON_NO_TEXTURE = "no-texture";

    /** Reason code reported when the planner rejects the request. */
    public static final String REASON_PLANNING_FAILED = "planning-failed";

    /** Catalog key prefix of this algorithm. */
    private static final String KEY_PREFIX = "turbo-mesh.algorithm.region.";

    private static final String NAME_KEY = KEY_PREFIX + "name";
    private static final String DESCRIPTION_KEY = KEY_PREFIX + "description";
    private static final String NO_TEXTURE_KEY = KEY_PREFIX + "rejected.no-texture";
    private static final String PLANNING_FAILED_KEY = KEY_PREFIX + "rejected.planning-failed";
    private static final String PRESET_SPARSE_KEY = KEY_PREFIX + "preset.sparse";
    private static final String PRESET_DETAILED_KEY = KEY_PREFIX + "preset.detailed";
    private static final String PRESET_HARD_SURFACE_KEY = KEY_PREFIX + "preset.hard-surface";

    /** Parameter ids; they are the canonical names of {@link RegionPlacementParams}. */
    static final String PARAMETER_MIN_REGION_RATIO = "minRegionRatio";

    static final String PARAMETER_AUTO_MIN_REGION_RATIO = "autoMinRegionRatio";
    static final String PARAMETER_CONTRAST = "contrast";
    static final String PARAMETER_AUTO_CONTRAST = "autoContrast";
    static final String PARAMETER_LEVEL = "level";
    static final String PARAMETER_TRACK_COUNT = "trackCount";
    static final String PARAMETER_MAX_SPACING = "maxSpacing";
    static final String PARAMETER_AUTO_MAX_SPACING = "autoMaxSpacing";
    static final String PARAMETER_SIMPLIFY_TOLERANCE = "simplifyTolerance";
    static final String PARAMETER_AUTO_SIMPLIFY_TOLERANCE = "autoSimplifyTolerance";
    static final String PARAMETER_MIN_SPACING = "minSpacing";
    static final String PARAMETER_AUTO_MIN_SPACING = "autoMinSpacing";

    /** Bounds and steps of the numeric controls; they mirror {@link RegionPlacementParams}. */
    private static final double MIN_REGION_RATIO_MIN = 0.0005;

    private static final double MIN_REGION_RATIO_MAX = 0.5;
    private static final double MIN_REGION_RATIO_STEP = 0.0005;
    private static final int MIN_REGION_RATIO_DECIMALS = 4;
    private static final double CONTRAST_MIN = 0.5;
    private static final double CONTRAST_MAX = 64.0;
    private static final double CONTRAST_STEP = 0.5;
    private static final int CONTRAST_DECIMALS = 1;
    private static final int LEVEL_MIN = 1;
    private static final int LEVEL_MAX = RegionPlacementParams.LEVEL_COUNT;
    private static final int TRACK_COUNT_MIN = 1;
    private static final int TRACK_COUNT_MAX = RegionPlacementParams.MAX_TRACK_COUNT;
    private static final int UNIT_STEP = 1;
    private static final double SPACING_MIN = 2.0;
    private static final double SPACING_MAX = 512.0;
    private static final double SPACING_STEP = 0.5;
    private static final int SPACING_DECIMALS = 1;
    private static final double SPACING_DEFAULT = 40.0;
    private static final double TOLERANCE_MIN = 0.1;
    private static final double TOLERANCE_MAX = 20.0;
    private static final double TOLERANCE_STEP = 0.05;
    private static final int TOLERANCE_DECIMALS = 2;
    private static final double TOLERANCE_DEFAULT = 2.0;
    private static final double MIN_SPACING_MIN = 0.5;
    private static final double MIN_SPACING_MAX = 40.0;
    private static final double MIN_SPACING_STEP = 0.1;
    private static final int MIN_SPACING_DECIMALS = 1;
    private static final double MIN_SPACING_DEFAULT = 4.0;

    private RegionAlgorithm() {}

    /**
     * Builds the algorithm declaration for one plugin generation.
     *
     * @param localization the plugin catalog that renders names, labels and preset names
     * @return the declaration the runtime registers
     */
    public static AutoMeshAlgorithm create(final PluginLocalization localization) {
        final List<AlgorithmParameterDescriptor> parameters = parameters(localization);
        return new AutoMeshAlgorithm(
                ID,
                localization.text(NAME_KEY),
                localization.text(DESCRIPTION_KEY),
                parameters,
                presets(localization),
                new RegionCompute(localization, parameters));
    }

    /** Catalog key of a parameter label. */
    private static String labelKey(final String parameterId) {
        return KEY_PREFIX + "parameter." + parameterId + ".label";
    }

    /** Catalog key of a parameter description. */
    private static String descriptionKey(final String parameterId) {
        return KEY_PREFIX + "parameter." + parameterId + ".description";
    }

    private static List<AlgorithmParameterDescriptor> parameters(final PluginLocalization localization) {
        return List.of(
                new AlgorithmParameterDescriptor.DoubleParameter(
                        PARAMETER_MIN_REGION_RATIO,
                        localization.text(labelKey(PARAMETER_MIN_REGION_RATIO)),
                        localization.text(descriptionKey(PARAMETER_MIN_REGION_RATIO)),
                        MIN_REGION_RATIO_MIN,
                        MIN_REGION_RATIO_MAX,
                        MIN_REGION_RATIO_STEP,
                        MIN_REGION_RATIO_DECIMALS,
                        RegionPlacementParams.DEFAULT_MIN_REGION_RATIO),
                new AlgorithmParameterDescriptor.BoolParameter(
                        PARAMETER_AUTO_MIN_REGION_RATIO,
                        localization.text(labelKey(PARAMETER_AUTO_MIN_REGION_RATIO)),
                        localization.text(descriptionKey(PARAMETER_AUTO_MIN_REGION_RATIO)),
                        true),
                new AlgorithmParameterDescriptor.DoubleParameter(
                        PARAMETER_CONTRAST,
                        localization.text(labelKey(PARAMETER_CONTRAST)),
                        localization.text(descriptionKey(PARAMETER_CONTRAST)),
                        CONTRAST_MIN,
                        CONTRAST_MAX,
                        CONTRAST_STEP,
                        CONTRAST_DECIMALS,
                        RegionPlacementParams.DEFAULT_CONTRAST),
                new AlgorithmParameterDescriptor.BoolParameter(
                        PARAMETER_AUTO_CONTRAST,
                        localization.text(labelKey(PARAMETER_AUTO_CONTRAST)),
                        localization.text(descriptionKey(PARAMETER_AUTO_CONTRAST)),
                        true),
                new AlgorithmParameterDescriptor.IntParameter(
                        PARAMETER_LEVEL,
                        localization.text(labelKey(PARAMETER_LEVEL)),
                        localization.text(descriptionKey(PARAMETER_LEVEL)),
                        LEVEL_MIN,
                        LEVEL_MAX,
                        UNIT_STEP,
                        RegionPlacementParams.DEFAULT_LEVEL),
                new AlgorithmParameterDescriptor.IntParameter(
                        PARAMETER_TRACK_COUNT,
                        localization.text(labelKey(PARAMETER_TRACK_COUNT)),
                        localization.text(descriptionKey(PARAMETER_TRACK_COUNT)),
                        TRACK_COUNT_MIN,
                        TRACK_COUNT_MAX,
                        UNIT_STEP,
                        RegionPlacementParams.DEFAULT_TRACK_COUNT),
                new AlgorithmParameterDescriptor.DoubleParameter(
                        PARAMETER_MAX_SPACING,
                        localization.text(labelKey(PARAMETER_MAX_SPACING)),
                        localization.text(descriptionKey(PARAMETER_MAX_SPACING)),
                        SPACING_MIN,
                        SPACING_MAX,
                        SPACING_STEP,
                        SPACING_DECIMALS,
                        SPACING_DEFAULT),
                new AlgorithmParameterDescriptor.BoolParameter(
                        PARAMETER_AUTO_MAX_SPACING,
                        localization.text(labelKey(PARAMETER_AUTO_MAX_SPACING)),
                        localization.text(descriptionKey(PARAMETER_AUTO_MAX_SPACING)),
                        true),
                new AlgorithmParameterDescriptor.DoubleParameter(
                        PARAMETER_SIMPLIFY_TOLERANCE,
                        localization.text(labelKey(PARAMETER_SIMPLIFY_TOLERANCE)),
                        localization.text(descriptionKey(PARAMETER_SIMPLIFY_TOLERANCE)),
                        TOLERANCE_MIN,
                        TOLERANCE_MAX,
                        TOLERANCE_STEP,
                        TOLERANCE_DECIMALS,
                        TOLERANCE_DEFAULT),
                new AlgorithmParameterDescriptor.BoolParameter(
                        PARAMETER_AUTO_SIMPLIFY_TOLERANCE,
                        localization.text(labelKey(PARAMETER_AUTO_SIMPLIFY_TOLERANCE)),
                        localization.text(descriptionKey(PARAMETER_AUTO_SIMPLIFY_TOLERANCE)),
                        true),
                new AlgorithmParameterDescriptor.DoubleParameter(
                        PARAMETER_MIN_SPACING,
                        localization.text(labelKey(PARAMETER_MIN_SPACING)),
                        localization.text(descriptionKey(PARAMETER_MIN_SPACING)),
                        MIN_SPACING_MIN,
                        MIN_SPACING_MAX,
                        MIN_SPACING_STEP,
                        MIN_SPACING_DECIMALS,
                        MIN_SPACING_DEFAULT),
                new AlgorithmParameterDescriptor.BoolParameter(
                        PARAMETER_AUTO_MIN_SPACING,
                        localization.text(labelKey(PARAMETER_AUTO_MIN_SPACING)),
                        localization.text(descriptionKey(PARAMETER_AUTO_MIN_SPACING)),
                        true));
    }

    /**
     * Presets the native selector offers next to the plugin's parameter controls.
     *
     * <p>Each preset projects one reviewed {@link RegionPlacementParams} constant, so the prototype
     * tuning the planner was verified against stays the only source of those numbers.</p>
     */
    private static List<AlgorithmPreset> presets(final PluginLocalization localization) {
        return List.of(
                preset(localization, PRESET_SPARSE_KEY, RegionPlacementParams.SPARSE),
                preset(localization, PRESET_DETAILED_KEY, RegionPlacementParams.DETAILED),
                preset(localization, PRESET_HARD_SURFACE_KEY, RegionPlacementParams.HARD_SURFACE));
    }

    private static AlgorithmPreset preset(
            final PluginLocalization localization, final String nameKey, final RegionPlacementParams params) {
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        putOptional(values, PARAMETER_MIN_REGION_RATIO, PARAMETER_AUTO_MIN_REGION_RATIO, params.minRegionRatio());
        putOptional(values, PARAMETER_CONTRAST, PARAMETER_AUTO_CONTRAST, params.contrast());
        values.put(PARAMETER_LEVEL, Integer.toString(params.level()));
        values.put(PARAMETER_TRACK_COUNT, Integer.toString(params.trackCount()));
        putOptional(values, PARAMETER_MAX_SPACING, PARAMETER_AUTO_MAX_SPACING, params.maxSpacing());
        putOptional(
                values, PARAMETER_SIMPLIFY_TOLERANCE, PARAMETER_AUTO_SIMPLIFY_TOLERANCE, params.simplifyTolerance());
        putOptional(values, PARAMETER_MIN_SPACING, PARAMETER_AUTO_MIN_SPACING, params.minSpacing());
        return new AlgorithmPreset(localization.text(nameKey), values);
    }

    private static void putOptional(
            final Map<String, String> values, final String valueId, final String autoId, final Double value) {
        values.put(autoId, Boolean.toString(value == null));
        if (value != null) {
            values.put(valueId, AlgorithmParameters.canonicalDecimal(value));
        }
    }

    private static RegionPlacementParams toParams(final AlgorithmParameters values) {
        return new RegionPlacementParams(
                optional(values, PARAMETER_MIN_REGION_RATIO, PARAMETER_AUTO_MIN_REGION_RATIO),
                optional(values, PARAMETER_CONTRAST, PARAMETER_AUTO_CONTRAST),
                values.integer(PARAMETER_LEVEL),
                values.integer(PARAMETER_TRACK_COUNT),
                optional(values, PARAMETER_MAX_SPACING, PARAMETER_AUTO_MAX_SPACING),
                optional(values, PARAMETER_SIMPLIFY_TOLERANCE, PARAMETER_AUTO_SIMPLIFY_TOLERANCE),
                optional(values, PARAMETER_MIN_SPACING, PARAMETER_AUTO_MIN_SPACING));
    }

    private static Double optional(final AlgorithmParameters values, final String valueId, final String autoId) {
        return values.flag(autoId) ? null : values.decimal(valueId);
    }

    /** Compute function of the region algorithm; it only reads the request. */
    private static final class RegionCompute implements AutoMeshCompute {

        private final PluginLocalization localization;
        private final List<AlgorithmParameterDescriptor> parameters;

        RegionCompute(final PluginLocalization localization, final List<AlgorithmParameterDescriptor> parameters) {
            this.localization = localization;
            this.parameters = parameters;
        }

        @Override
        public AutoMeshResult compute(final AutoMeshRequest request, final CancellationToken cancellation) {
            if (cancellation.isCancellationRequested()) {
                return AutoMeshResult.Cancelled.INSTANCE;
            }
            final TexturePixels texture = request.texture().orElse(null);
            if (texture == null) {
                return new AutoMeshResult.Rejected(REASON_NO_TEXTURE, localization.text(NO_TEXTURE_KEY));
            }
            try {
                final RegionPlacementParams params =
                        toParams(AlgorithmParameters.of(parameters, request.parameterValues()));
                final RegionPlacementPlan plan =
                        RegionPlacementPlanner.plan(texture.argb(), texture.width(), texture.height(), params);
                if (cancellation.isCancellationRequested()) {
                    return AutoMeshResult.Cancelled.INSTANCE;
                }
                return new AutoMeshResult.Success(toGeometry(plan, texture, request.geometry()), summary(plan));
            } catch (CancellationException cancelled) {
                return AutoMeshResult.Cancelled.INSTANCE;
            } catch (IllegalArgumentException | IllegalStateException failure) {
                return new AutoMeshResult.Rejected(REASON_PLANNING_FAILED, rejection(failure));
            }
        }

        private String rejection(final RuntimeException failure) {
            final String detail = failure.getMessage() == null ? failure.toString() : failure.getMessage();
            return RejectionText.of(localization.text(PLANNING_FAILED_KEY), detail);
        }

        private static String summary(final RegionPlacementPlan plan) {
            return "regions=" + plan.regions().regionCount()
                    + ", vertices=" + plan.vertexCountAfterMerge()
                    + ", triangles=" + plan.triangleCount();
        }

        /**
         * Converts one plan into mesh geometry.
         *
         * <p>The plan works in raster pixels, so each vertex travels back through the host affine,
         * and the resulting uv sample resolves into position space through the same mapper the
         * context-menu placement uses. Both directions stay in one place, which keeps the algorithm
         * result identical to that path.</p>
         */
        private static ArtMeshGeometry toGeometry(
                final RegionPlacementPlan plan, final TexturePixels texture, final ArtMeshGeometry source) {
            final TextureUvMapping pixelToUv =
                    TextureUvMapping.of(texture.width(), texture.height(), texture.uvToPixel());
            final UvToPosition uvToPosition =
                    UvToPosition.of(source.uvs(), source.positions(), source.triangleIndices());
            final List<Point2> uvs = new ArrayList<>(plan.vertices().size());
            final List<Point2> positions = new ArrayList<>(plan.vertices().size());
            for (final Point2 vertex : plan.vertices()) {
                final Point2 uv = pixelToUv.map(vertex.x(), vertex.y());
                uvs.add(uv);
                positions.add(uvToPosition.map(uv.x(), uv.y()));
            }
            return new ArtMeshGeometry(positions, uvs, triangleIndices(plan.triangleIndices()));
        }

        private static List<Integer> triangleIndices(final int[] indices) {
            final List<Integer> boxed = new ArrayList<>(indices.length);
            for (final int index : indices) {
                boxed.add(index);
            }
            return boxed;
        }
    }
}
