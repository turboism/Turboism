package dev.turboism.plugin.turbomesh.domain.features;

/** Tunables for the 0.2.0 feature-aware placement path. All lengths are UV units. */
public record FeatureOptions(
        boolean enabled,
        DensityPreset preset,
        double boundaryBandUv,
        double cornerBandUv,
        double narrowWidthUv,
        double weightBoundary,
        double weightCorner,
        double weightNarrow,
        double weightTexture,
        double textureContrast,
        double directionInfluence,
        double baseDensity,
        double minRadiusFactor,
        double cornerTurnRadians,
        int cornerScales) {

    public FeatureOptions {
        if (preset == null) {
            throw new IllegalArgumentException("preset is required");
        }
        if (!(boundaryBandUv > 0.0) || !(cornerBandUv > 0.0) || !(narrowWidthUv > 0.0)) {
            throw new IllegalArgumentException("feature length settings must be positive");
        }
        if (weightBoundary < 0.0 || weightCorner < 0.0 || weightNarrow < 0.0 || weightTexture < 0.0) {
            throw new IllegalArgumentException("feature weights must be non-negative");
        }
        if (!(textureContrast > 0.0)) {
            throw new IllegalArgumentException("textureContrast must be positive");
        }
        if (!(directionInfluence >= 0.0) || !(directionInfluence <= 1.0)) {
            throw new IllegalArgumentException("directionInfluence must lie in [0, 1]");
        }
        if (!(baseDensity >= 0.0) || !(baseDensity <= 1.0)) {
            throw new IllegalArgumentException("baseDensity must lie in [0, 1]");
        }
        if (!(minRadiusFactor > 0.0) || !(minRadiusFactor <= 1.0)) {
            throw new IllegalArgumentException("minRadiusFactor must lie in (0, 1]");
        }
        if (!(cornerTurnRadians > 0.0) || cornerScales < 1) {
            throw new IllegalArgumentException("corner settings are out of range");
        }
    }

    /** The feature path is off: the planner must reproduce the 0.1.0 output exactly. */
    public static FeatureOptions disabled() {
        return new FeatureOptions(
                false,
                DensityPreset.AUTO_BLEND,
                0.05,
                0.03,
                0.08,
                0.5,
                0.3,
                0.2,
                0.0,
                0.25,
                0.0,
                0.25,
                0.35,
                Math.toRadians(40.0),
                3);
    }

    /** Sensible defaults for geometry-aware adaptive placement. */
    public static FeatureOptions defaults() {
        return forPreset(DensityPreset.AUTO_BLEND);
    }

    /** Preset-fixed starting points; every knob stays overridable through the factory below. */
    public static FeatureOptions forPreset(final DensityPreset preset) {
        return switch (preset) {
            case AUTO_BLEND ->
                new FeatureOptions(
                        true,
                        preset,
                        0.05,
                        0.03,
                        0.08,
                        0.5,
                        0.3,
                        0.2,
                        0.35,
                        0.25,
                        0.3,
                        0.25,
                        0.35,
                        Math.toRadians(40.0),
                        3);
            case SOFT_STRIP ->
                new FeatureOptions(
                        true,
                        preset,
                        0.04,
                        0.025,
                        0.06,
                        0.45,
                        0.25,
                        0.55,
                        0.45,
                        0.2,
                        0.6,
                        0.25,
                        0.3,
                        Math.toRadians(45.0),
                        3);
            case SOFT_SHEET ->
                new FeatureOptions(
                        true,
                        preset,
                        0.06,
                        0.04,
                        0.12,
                        0.5,
                        0.3,
                        0.1,
                        0.25,
                        0.25,
                        0.2,
                        0.3,
                        0.45,
                        Math.toRadians(40.0),
                        3);
            case RIGID_GEOMETRY ->
                new FeatureOptions(
                        true,
                        preset,
                        0.05,
                        0.03,
                        0.08,
                        0.6,
                        0.5,
                        0.1,
                        0.05,
                        0.3,
                        0.05,
                        0.15,
                        0.5,
                        Math.toRadians(35.0),
                        4);
        };
    }

    /** Copy of this option set with {@code enabled} replaced. */
    public FeatureOptions withEnabled(final boolean value) {
        return new FeatureOptions(
                value,
                preset,
                boundaryBandUv,
                cornerBandUv,
                narrowWidthUv,
                weightBoundary,
                weightCorner,
                weightNarrow,
                weightTexture,
                textureContrast,
                directionInfluence,
                baseDensity,
                minRadiusFactor,
                cornerTurnRadians,
                cornerScales);
    }

    /** Copy of this option set with {@code directionInfluence} replaced. */
    public FeatureOptions withDirectionInfluence(final double value) {
        return new FeatureOptions(
                enabled,
                preset,
                boundaryBandUv,
                cornerBandUv,
                narrowWidthUv,
                weightBoundary,
                weightCorner,
                weightNarrow,
                weightTexture,
                textureContrast,
                value,
                baseDensity,
                minRadiusFactor,
                cornerTurnRadians,
                cornerScales);
    }

    /** System-property overrides; the feature path stays opt-in until the 0.2.0 settings UX. */
    public static FeatureOptions fromSystemProperties() {
        final FeatureOptions base = defaults();
        final boolean enabled = Boolean.parseBoolean(
                System.getProperty("turboMesh.features", "false").trim());
        final DensityPreset preset =
                parsePreset(System.getProperty("turboMesh.features.preset", "").trim());
        final FeatureOptions fixed = preset == null ? base : forPreset(preset);
        return new FeatureOptions(
                enabled,
                fixed.preset(),
                doubleProperty("turboMesh.features.boundaryBandUv", fixed.boundaryBandUv(), 0.004, 0.25),
                doubleProperty("turboMesh.features.cornerBandUv", fixed.cornerBandUv(), 0.002, 0.25),
                doubleProperty("turboMesh.features.narrowWidthUv", fixed.narrowWidthUv(), 0.004, 0.5),
                doubleProperty("turboMesh.features.weightBoundary", fixed.weightBoundary(), 0.0, 4.0),
                doubleProperty("turboMesh.features.weightCorner", fixed.weightCorner(), 0.0, 4.0),
                doubleProperty("turboMesh.features.weightNarrow", fixed.weightNarrow(), 0.0, 4.0),
                doubleProperty("turboMesh.features.weightTexture", fixed.weightTexture(), 0.0, 4.0),
                doubleProperty("turboMesh.features.textureContrast", fixed.textureContrast(), 0.02, 1.0),
                doubleProperty("turboMesh.features.directionInfluence", fixed.directionInfluence(), 0.0, 1.0),
                doubleProperty("turboMesh.features.baseDensity", fixed.baseDensity(), 0.0, 1.0),
                doubleProperty("turboMesh.features.minRadiusFactor", fixed.minRadiusFactor(), 0.05, 1.0),
                doubleProperty(
                        "turboMesh.features.cornerTurnRadians",
                        fixed.cornerTurnRadians(),
                        Math.toRadians(5.0),
                        Math.toRadians(170.0)),
                intProperty("turboMesh.features.cornerScales", fixed.cornerScales(), 1, 6));
    }

    private static DensityPreset parsePreset(final String name) {
        if (name.isEmpty()) {
            return null;
        }
        for (final DensityPreset preset : DensityPreset.values()) {
            if (preset.name().equalsIgnoreCase(name)) {
                return preset;
            }
        }
        return null;
    }

    private static double doubleProperty(final String name, final double fallback, final double min, final double max) {
        try {
            final double value = Double.parseDouble(System.getProperty(name, "").trim());
            return Math.max(min, Math.min(max, value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

    private static int intProperty(final String name, final int fallback, final int min, final int max) {
        try {
            final int value = Integer.parseInt(System.getProperty(name, "").trim());
            return Math.max(min, Math.min(max, value));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
