package dev.turboism.mapping.verification;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Exact selector shape required by the Core public-API provider admission seam.
 *
 * <p>This is deliberately not a trust-root manifest: it contains no reviewed record digest or
 * artifact allowlist and cannot create a resolver. A separately reviewed static verification
 * record must authorize the complete profile-specific contract before production admission is
 * possible.</p>
 */
public final class CorePublicApiSelectorContract {

    public static final String ADAPTER_SLICE_ID = "adapter.core-model.readonly";
    public static final Set<String> CAPABILITY_IDS = Set.of("cubism.geometry.read");

    public static final String LIVE2D_CORE_CLASS = "cubism.core.live2d.class";
    public static final String CORE_VERSION_CLASS = "cubism.core.version.class";
    public static final String MODEL_CLASS = "cubism.core.model.class";
    public static final String CANVAS_INFO_CLASS = "cubism.core.canvas-info.class";
    public static final String PARAMETERS_CLASS = "cubism.core.parameters.class";
    public static final String PARAMETER_TYPE_CLASS = "cubism.core.parameter-type.class";

    public static final String GET_VERSION = "cubism.core.live2d.get-version";
    public static final String GET_MAJOR = "cubism.core.version.major";
    public static final String GET_MINOR = "cubism.core.version.minor";
    public static final String GET_PATCH = "cubism.core.version.patch";

    public static final String MODEL_GET_CANVAS_INFO =
        "cubism.core.model.get-canvas-info";
    public static final String MODEL_GET_PARAMETERS =
        "cubism.core.model.get-parameters";
    public static final String CANVAS_GET_SIZE_IN_PIXELS =
        "cubism.core.canvas-info.size-in-pixels";
    public static final String CANVAS_GET_ORIGIN_IN_PIXELS =
        "cubism.core.canvas-info.origin-in-pixels";
    public static final String CANVAS_GET_PIXELS_PER_UNIT =
        "cubism.core.canvas-info.pixels-per-unit";

    public static final String PARAMETERS_GET_COUNT =
        "cubism.core.parameters.count";
    public static final String PARAMETERS_GET_DEFAULT_VALUES =
        "cubism.core.parameters.default-values";
    public static final String PARAMETERS_GET_IDS =
        "cubism.core.parameters.ids";
    public static final String PARAMETERS_GET_KEY_COUNTS =
        "cubism.core.parameters.key-counts";
    public static final String PARAMETERS_GET_KEY_VALUES =
        "cubism.core.parameters.key-values";
    public static final String PARAMETERS_GET_MAXIMUM_VALUES =
        "cubism.core.parameters.maximum-values";
    public static final String PARAMETERS_GET_MINIMUM_VALUES =
        "cubism.core.parameters.minimum-values";
    public static final String PARAMETERS_GET_TYPES =
        "cubism.core.parameters.types";
    public static final String PARAMETERS_GET_VALUES =
        "cubism.core.parameters.values";
    public static final String PARAMETERS_GET_REPEATS =
        "cubism.core.parameters.repeats";
    public static final String PARAMETER_TYPE_GET_NUMBER =
        "cubism.core.parameter-type.number";

    public static final Set<String> VERSION_PROBE_ALIASES = Set.of(
        LIVE2D_CORE_CLASS,
        CORE_VERSION_CLASS,
        GET_VERSION,
        GET_MAJOR,
        GET_MINOR,
        GET_PATCH
    );

    public static final Set<String> COMMON_STRUCTURAL_ALIASES = Set.of(
        MODEL_CLASS,
        CANVAS_INFO_CLASS,
        PARAMETERS_CLASS,
        PARAMETER_TYPE_CLASS,
        MODEL_GET_CANVAS_INFO,
        MODEL_GET_PARAMETERS,
        CANVAS_GET_SIZE_IN_PIXELS,
        CANVAS_GET_ORIGIN_IN_PIXELS,
        CANVAS_GET_PIXELS_PER_UNIT,
        PARAMETERS_GET_COUNT,
        PARAMETERS_GET_DEFAULT_VALUES,
        PARAMETERS_GET_IDS,
        PARAMETERS_GET_KEY_COUNTS,
        PARAMETERS_GET_KEY_VALUES,
        PARAMETERS_GET_MAXIMUM_VALUES,
        PARAMETERS_GET_MINIMUM_VALUES,
        PARAMETERS_GET_TYPES,
        PARAMETERS_GET_VALUES,
        PARAMETER_TYPE_GET_NUMBER
    );

    public static final String ARTIFACT_PROFILE_5_2 = "5.2";
    public static final String ARTIFACT_PROFILE_5_3_02 = "5.3.02";
    public static final Set<String> SUPPORTED_ARTIFACT_PROFILES = Set.of(
        ARTIFACT_PROFILE_5_2,
        ARTIFACT_PROFILE_5_3_02
    );

    public static final Set<String> REQUIRED_ALIASES_5_2 =
        union(VERSION_PROBE_ALIASES, COMMON_STRUCTURAL_ALIASES);
    public static final Set<String> REQUIRED_ALIASES_5_3_02 =
        union(REQUIRED_ALIASES_5_2, Set.of(PARAMETERS_GET_REPEATS));

    private CorePublicApiSelectorContract() {
    }

    public static Optional<Set<String>> requiredAliasesFor(
        final String artifactProfile
    ) {
        Objects.requireNonNull(artifactProfile, "artifactProfile");
        return switch (artifactProfile) {
            case ARTIFACT_PROFILE_5_2 -> Optional.of(REQUIRED_ALIASES_5_2);
            case ARTIFACT_PROFILE_5_3_02 -> Optional.of(REQUIRED_ALIASES_5_3_02);
            default -> Optional.empty();
        };
    }

    public static Optional<String> providerIdFor(final String artifactProfile) {
        Objects.requireNonNull(artifactProfile, "artifactProfile");
        return switch (artifactProfile) {
            case ARTIFACT_PROFILE_5_2 -> Optional.of("cubism-core-public-5.2");
            case ARTIFACT_PROFILE_5_3_02 -> Optional.of("cubism-core-public-5.3.02");
            default -> Optional.empty();
        };
    }

    private static Set<String> union(
        final Set<String> first,
        final Set<String> second
    ) {
        final java.util.HashSet<String> combined = new java.util.HashSet<>(first);
        combined.addAll(second);
        return Set.copyOf(combined);
    }
}
