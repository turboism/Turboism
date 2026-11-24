package dev.turboism.plugin.turbomesh.algorithm;

import dev.turboism.plugin.turbomesh.domain.PlacementDomainPlanner;
import dev.turboism.plugin.turbomesh.domain.PlacementSettings;
import dev.turboism.plugin.turbomesh.domain.TurboMeshPlanner;
import dev.turboism.plugin.turbomesh.domain.features.DensityPreset;
import dev.turboism.plugin.turbomesh.domain.features.FeatureOptions;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmPreset;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshAlgorithm;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshCompute;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshRequest;
import dev.turboism.sdk.cubism.mesh.automesh.AutoMeshResult;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.CancellationToken;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/**
 * Adaptive placement published as an auto-mesh algorithm.
 *
 * <p>This declaration exposes the planner the ArtMesh context menu already uses, so the Editor's
 * Auto Mesh Generate entry can run the same geometry-only strategy without a dialog. The density
 * parameter selects one {@link DensityPreset}; the remaining parameters replace the four spacing
 * knobs of {@link PlacementSettings} that a user legitimately tunes.</p>
 */
public final class AdaptiveAlgorithm {

    /** Stable algorithm id; the runtime keys the session selection by it. */
    public static final String ID = "turbo-mesh.adaptive";

    /** Reason code reported when the planner rejects the request. */
    public static final String REASON_PLANNING_FAILED = "planning-failed";

    /** Catalog key prefix of this algorithm. */
    private static final String KEY_PREFIX = "turbo-mesh.algorithm.adaptive.";

    private static final String NAME_KEY = KEY_PREFIX + "name";
    private static final String DESCRIPTION_KEY = KEY_PREFIX + "description";
    private static final String PLANNING_FAILED_KEY = KEY_PREFIX + "rejected.planning-failed";

    /** Parameter ids; each one names the {@link PlacementSettings} field it overrides. */
    static final String PARAMETER_DENSITY = "density";

    static final String PARAMETER_MAX_BOUNDARY_SPACING = "maxBoundarySpacing";
    static final String PARAMETER_INTERIOR_SPACING = "interiorSpacing";
    static final String PARAMETER_MIN_SPACING = "minSpacing";
    static final String PARAMETER_BLEED_MARGIN = "bleedMargin";
    static final String PARAMETER_MAX_POINTS = "maxPoints";

    /** Bounds and steps of the numeric controls; they mirror the planner's own limits. */
    private static final double BOUNDARY_SPACING_MIN = 0.005;

    private static final double BOUNDARY_SPACING_MAX = 0.5;
    private static final double BOUNDARY_SPACING_STEP = 0.005;
    private static final int BOUNDARY_SPACING_DECIMALS = 3;
    private static final double DEFAULT_MAX_BOUNDARY_SPACING = 0.06;
    private static final double INTERIOR_SPACING_MIN = 0.005;
    private static final double INTERIOR_SPACING_MAX = 1.0;
    private static final double INTERIOR_SPACING_STEP = 0.005;
    private static final int INTERIOR_SPACING_DECIMALS = 3;
    private static final double DEFAULT_INTERIOR_SPACING = 0.10;
    private static final double MIN_SPACING_MIN = 0.001;
    private static final double MIN_SPACING_MAX = 0.1;
    private static final double MIN_SPACING_STEP = 0.001;
    private static final int MIN_SPACING_DECIMALS = 3;
    private static final double DEFAULT_MIN_SPACING = 0.005;
    private static final double BLEED_MARGIN_MIN = 0.0;
    private static final double BLEED_MARGIN_MAX = 64.0;
    private static final double BLEED_MARGIN_STEP = 0.5;
    private static final int BLEED_MARGIN_DECIMALS = 1;
    private static final double DEFAULT_BLEED_MARGIN = PlacementSettings.DEFAULT_BLEED_MARGIN_PX;
    private static final double RIGID_BLEED_MARGIN = 5.0;
    private static final double STRIP_BOUNDARY_SPACING = 0.045;
    private static final double STRIP_INTERIOR_SPACING = 0.075;
    private static final double STRIP_MIN_SPACING = 0.004;
    private static final double SHEET_BOUNDARY_SPACING = 0.065;
    private static final double SHEET_INTERIOR_SPACING = 0.095;
    private static final double SHEET_MIN_SPACING = 0.006;
    private static final double RIGID_BOUNDARY_SPACING = 0.09;
    private static final double RIGID_INTERIOR_SPACING = 0.18;
    private static final double RIGID_MIN_SPACING = 0.008;
    private static final int MAX_POINTS_MIN = 64;
    private static final int MAX_POINTS_MAX = 65536;
    private static final int MAX_POINTS_STEP = 64;

    /**
     * The compute returns one layout for the whole mesh and reads no stored keyform data, so it
     * declares no per-keyform capability.
     */
    private static final boolean SUPPORTS_MULTI_KEYFORM = false;

    private AdaptiveAlgorithm() {}

    /**
     * Builds the algorithm declaration for one plugin generation.
     *
     * @param localization the plugin catalog that renders names, labels and preset names
     * @return the declaration the runtime registers
     */
    public static AutoMeshAlgorithm create(final PluginLocalization localization) {
        return create(localization, ignored -> {});
    }

    /**
     * Builds the algorithm declaration with a diagnostics sink.
     *
     * @param localization the plugin catalog that renders names, labels and preset names
     * @param diagnostics receives one line per run that describes the planning domain
     * @return the declaration the runtime registers
     */
    public static AutoMeshAlgorithm create(final PluginLocalization localization, final Consumer<String> diagnostics) {
        final List<AlgorithmParameterDescriptor> parameters = parameters(localization);
        return new AutoMeshAlgorithm(
                ID,
                localization.text(NAME_KEY),
                localization.text(DESCRIPTION_KEY),
                parameters,
                presets(localization),
                SUPPORTS_MULTI_KEYFORM,
                new AdaptiveCompute(localization, parameters, diagnostics));
    }

    /** Catalog key of a parameter label. */
    private static String labelKey(final String parameterId) {
        return KEY_PREFIX + "parameter." + parameterId + ".label";
    }

    /** Catalog key of a parameter description. */
    private static String descriptionKey(final String parameterId) {
        return KEY_PREFIX + "parameter." + parameterId + ".description";
    }

    /** Catalog key of a density option label. */
    private static String optionKey(final DensityPreset preset) {
        return KEY_PREFIX + "option.density." + preset.keyToken();
    }

    /** Catalog key of a preset name. */
    private static String presetKey(final DensityPreset preset) {
        return KEY_PREFIX + "preset." + preset.keyToken();
    }

    private static List<AlgorithmParameterDescriptor> parameters(final PluginLocalization localization) {
        final PlacementSettings defaults = PlacementSettings.defaults();
        final List<AlgorithmParameterDescriptor.EnumParameter.Option> options = new ArrayList<>();
        for (final DensityPreset preset : DensityPreset.values()) {
            options.add(new AlgorithmParameterDescriptor.EnumParameter.Option(
                    preset.name(), localization.text(optionKey(preset))));
        }
        return List.of(
                new AlgorithmParameterDescriptor.EnumParameter(
                        PARAMETER_DENSITY,
                        localization.text(labelKey(PARAMETER_DENSITY)),
                        localization.text(descriptionKey(PARAMETER_DENSITY)),
                        options,
                        DensityPreset.AUTO_BLEND.name()),
                new AlgorithmParameterDescriptor.DoubleParameter(
                        PARAMETER_MAX_BOUNDARY_SPACING,
                        localization.text(labelKey(PARAMETER_MAX_BOUNDARY_SPACING)),
                        localization.text(descriptionKey(PARAMETER_MAX_BOUNDARY_SPACING)),
                        BOUNDARY_SPACING_MIN,
                        BOUNDARY_SPACING_MAX,
                        BOUNDARY_SPACING_STEP,
                        BOUNDARY_SPACING_DECIMALS,
                        DEFAULT_MAX_BOUNDARY_SPACING),
                new AlgorithmParameterDescriptor.DoubleParameter(
                        PARAMETER_INTERIOR_SPACING,
                        localization.text(labelKey(PARAMETER_INTERIOR_SPACING)),
                        localization.text(descriptionKey(PARAMETER_INTERIOR_SPACING)),
                        INTERIOR_SPACING_MIN,
                        INTERIOR_SPACING_MAX,
                        INTERIOR_SPACING_STEP,
                        INTERIOR_SPACING_DECIMALS,
                        DEFAULT_INTERIOR_SPACING),
                new AlgorithmParameterDescriptor.DoubleParameter(
                        PARAMETER_MIN_SPACING,
                        localization.text(labelKey(PARAMETER_MIN_SPACING)),
                        localization.text(descriptionKey(PARAMETER_MIN_SPACING)),
                        MIN_SPACING_MIN,
                        MIN_SPACING_MAX,
                        MIN_SPACING_STEP,
                        MIN_SPACING_DECIMALS,
                        DEFAULT_MIN_SPACING),
                new AlgorithmParameterDescriptor.DoubleParameter(
                        PARAMETER_BLEED_MARGIN,
                        localization.text(labelKey(PARAMETER_BLEED_MARGIN)),
                        localization.text(descriptionKey(PARAMETER_BLEED_MARGIN)),
                        BLEED_MARGIN_MIN,
                        BLEED_MARGIN_MAX,
                        BLEED_MARGIN_STEP,
                        BLEED_MARGIN_DECIMALS,
                        DEFAULT_BLEED_MARGIN),
                new AlgorithmParameterDescriptor.IntParameter(
                        PARAMETER_MAX_POINTS,
                        localization.text(labelKey(PARAMETER_MAX_POINTS)),
                        localization.text(descriptionKey(PARAMETER_MAX_POINTS)),
                        MAX_POINTS_MIN,
                        MAX_POINTS_MAX,
                        MAX_POINTS_STEP,
                        defaults.maxPoints()));
    }

    /**
     * Presets the native selector offers: one per density preset with tailored spacing and bleed.
     */
    private static List<AlgorithmPreset> presets(final PluginLocalization localization) {
        final List<AlgorithmPreset> presets = new ArrayList<>(DensityPreset.values().length);
        for (final DensityPreset preset : DensityPreset.values()) {
            final LinkedHashMap<String, String> values = new LinkedHashMap<>();
            values.put(PARAMETER_DENSITY, preset.name());
            switch (preset) {
                case AUTO_BLEND -> {
                    values.put(
                            PARAMETER_MAX_BOUNDARY_SPACING,
                            AlgorithmParameters.canonicalDecimal(DEFAULT_MAX_BOUNDARY_SPACING));
                    values.put(
                            PARAMETER_INTERIOR_SPACING, AlgorithmParameters.canonicalDecimal(DEFAULT_INTERIOR_SPACING));
                    values.put(PARAMETER_MIN_SPACING, AlgorithmParameters.canonicalDecimal(DEFAULT_MIN_SPACING));
                    values.put(PARAMETER_BLEED_MARGIN, AlgorithmParameters.canonicalDecimal(DEFAULT_BLEED_MARGIN));
                }
                case SOFT_STRIP -> {
                    values.put(
                            PARAMETER_MAX_BOUNDARY_SPACING,
                            AlgorithmParameters.canonicalDecimal(STRIP_BOUNDARY_SPACING));
                    values.put(
                            PARAMETER_INTERIOR_SPACING, AlgorithmParameters.canonicalDecimal(STRIP_INTERIOR_SPACING));
                    values.put(PARAMETER_MIN_SPACING, AlgorithmParameters.canonicalDecimal(STRIP_MIN_SPACING));
                    values.put(PARAMETER_BLEED_MARGIN, AlgorithmParameters.canonicalDecimal(DEFAULT_BLEED_MARGIN));
                }
                case SOFT_SHEET -> {
                    values.put(
                            PARAMETER_MAX_BOUNDARY_SPACING,
                            AlgorithmParameters.canonicalDecimal(SHEET_BOUNDARY_SPACING));
                    values.put(
                            PARAMETER_INTERIOR_SPACING, AlgorithmParameters.canonicalDecimal(SHEET_INTERIOR_SPACING));
                    values.put(PARAMETER_MIN_SPACING, AlgorithmParameters.canonicalDecimal(SHEET_MIN_SPACING));
                    values.put(PARAMETER_BLEED_MARGIN, AlgorithmParameters.canonicalDecimal(DEFAULT_BLEED_MARGIN));
                }
                case RIGID_GEOMETRY -> {
                    values.put(
                            PARAMETER_MAX_BOUNDARY_SPACING,
                            AlgorithmParameters.canonicalDecimal(RIGID_BOUNDARY_SPACING));
                    values.put(
                            PARAMETER_INTERIOR_SPACING, AlgorithmParameters.canonicalDecimal(RIGID_INTERIOR_SPACING));
                    values.put(PARAMETER_MIN_SPACING, AlgorithmParameters.canonicalDecimal(RIGID_MIN_SPACING));
                    values.put(PARAMETER_BLEED_MARGIN, AlgorithmParameters.canonicalDecimal(RIGID_BLEED_MARGIN));
                }
            }
            presets.add(new AlgorithmPreset(localization.text(presetKey(preset)), values));
        }
        return presets;
    }

    private static DensityPreset toPreset(final AlgorithmParameters values) {
        return DensityPreset.valueOf(values.option(PARAMETER_DENSITY));
    }

    private static PlacementSettings toSettings(final AlgorithmParameters values) {
        return PlacementSettings.defaults()
                .withDensityAndBleed(
                        values.decimal(PARAMETER_MAX_BOUNDARY_SPACING),
                        values.decimal(PARAMETER_INTERIOR_SPACING),
                        values.decimal(PARAMETER_MIN_SPACING),
                        values.integer(PARAMETER_MAX_POINTS),
                        values.decimal(PARAMETER_BLEED_MARGIN));
    }

    /** Compute function of the adaptive algorithm; it only reads the request. */
    private static final class AdaptiveCompute implements AutoMeshCompute {

        private final PluginLocalization localization;
        private final List<AlgorithmParameterDescriptor> parameters;
        private final Consumer<String> diagnostics;

        AdaptiveCompute(
                final PluginLocalization localization,
                final List<AlgorithmParameterDescriptor> parameters,
                final Consumer<String> diagnostics) {
            this.localization = localization;
            this.parameters = parameters;
            this.diagnostics = diagnostics;
        }

        @Override
        public AutoMeshResult compute(final AutoMeshRequest request, final CancellationToken cancellation) {
            if (cancellation.isCancellationRequested()) {
                return AutoMeshResult.Cancelled.INSTANCE;
            }
            try {
                final AlgorithmParameters values = AlgorithmParameters.of(parameters, request.parameterValues());
                final PlacementSettings settings = toSettings(values);
                final FeatureOptions options = FeatureOptions.forPreset(toPreset(values));
                final TurboMeshPlanner.PlacementPlan plan = PlacementDomainPlanner.plan(
                        request.geometry(), request.texture().orElse(null), settings, options, diagnostics);
                if (cancellation.isCancellationRequested()) {
                    return AutoMeshResult.Cancelled.INSTANCE;
                }
                return new AutoMeshResult.Success(plan.geometry(), summary(plan));
            } catch (CancellationException cancelled) {
                return AutoMeshResult.Cancelled.INSTANCE;
            } catch (TurboMeshPlanner.PlacementException | IllegalArgumentException | IllegalStateException failure) {
                return new AutoMeshResult.Rejected(REASON_PLANNING_FAILED, rejection(failure.getMessage()));
            }
        }

        private String rejection(final String detail) {
            return RejectionText.of(localization.text(PLANNING_FAILED_KEY), detail);
        }

        private static String summary(final TurboMeshPlanner.PlacementPlan plan) {
            final TurboMeshPlanner.Stats stats = plan.stats();
            return "regions=" + stats.regionCount()
                    + ", boundary=" + stats.boundaryPoints()
                    + ", interior=" + stats.interiorPoints()
                    + ", triangles=" + stats.triangleCount();
        }
    }
}
