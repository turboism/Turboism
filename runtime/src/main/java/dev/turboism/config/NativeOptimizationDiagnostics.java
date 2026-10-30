package dev.turboism.config;

import dev.turboism.config.NativeOptimizationConfig.Choice;
import dev.turboism.config.NativeOptimizationConfig.Option;
import java.util.EnumMap;
import java.util.Map;

/** Startup facts kept separately from editable preferences; never infers installation from a checkbox. */
public final class NativeOptimizationDiagnostics {
    /** Requested startup state and the outcome of the verified installation attempt. */
    public record Status(Choice choice, String state, String reason) {}

    private static final Map<Option, Status> STATUSES = new EnumMap<>(Option.class);

    private static PerformanceSettingsConfig startup;
    private static final Map<PerformanceSettingsConfig.Field, String> OBSERVED =
            new EnumMap<>(PerformanceSettingsConfig.Field.class);
    /** Publishes the complete snapshot captured before any performance hook installs. */
    public static synchronized void startup(PerformanceSettingsConfig snapshot) {
        startup = snapshot;
        STATUSES.clear();
        OBSERVED.clear();
        for (Option option : Option.values()) {
            var value = snapshot.value(option.field());
            requested(option, new Choice(Boolean.parseBoolean(value.value()), value.source()));
        }
    }
    /** Clears the launch snapshot and observed statuses for isolated test suites. */
    public static synchronized void resetForTesting() {
        startup = null;
        STATUSES.clear();
        OBSERVED.clear();
    }
    /** Current launch snapshot; absent when bootstrap was not observed. */
    public static synchronized PerformanceSettingsConfig startup() {
        return startup;
    }
    /** Records a result observed from the live host, rather than inferring it from a preference. */
    public static synchronized void observed(PerformanceSettingsConfig.Field field, String result) {
        OBSERVED.put(field, result);
    }
    /** Last host observation for a non-transformer preference. */
    public static synchronized String observed(PerformanceSettingsConfig.Field field) {
        return OBSERVED.getOrDefault(field, "not-observed");
    }

    private NativeOptimizationDiagnostics() {}

    /** Records the resolved preference without replacing an observed installation outcome. */
    public static synchronized void requested(Option option, Choice choice) {
        final Status previous = STATUSES.get(option);
        STATUSES.put(
                option,
                previous == null
                        ? new Status(
                                choice, "not-installed", choice.enabled() ? "not-attempted" : "preference-disabled")
                        : new Status(choice, previous.state(), previous.reason()));
    }

    /** Records an actual contributor result for the configurable native optimizations. */
    public static synchronized void installed(String protocol, String state, String reason) {
        final Option option =
                switch (protocol) {
                    case "TURBOISM_MESH_TRIANGULATION_HASH" -> Option.MESH_TRIANGULATION_HASH;
                    case "TURBOISM_TRIANGULATION_EDGE_INDEX" -> Option.TRIANGULATION_EDGE_INDEX;
                    case "TURBOISM_ATLAS_TILE_BBOX" -> Option.ATLAS_TILE_BBOX;
                    case "TURBOISM_ATLAS_CACHE_REUSE" -> Option.ATLAS_CACHE_REUSE;
                    case "TURBOISM_MODEL_UPDATE_SKIP" -> Option.MODEL_UPDATE_SKIP;
                    case "TURBOISM_INCREMENTAL_UPDATE" -> Option.INCREMENTAL_UPDATE;
                    case "TURBOISM_UNIFORM_LOCATION" -> Option.UNIFORM_LOCATION_CACHE;
                    case "TURBOISM_UPLOAD_ELISION" -> Option.UPLOAD_ELISION;
                    case "TURBOISM_INPUT_PATH" -> Option.INPUT_PATH_ELISION;
                    case "TURBOISM_DEFERRED_GL_ERROR_CHECK" -> Option.MESA_GL_THREAD;
                    case "TURBOISM_MESH_INTERPOLATION" -> Option.MESH_INTERPOLATION;
                    case "TURBOISM_SHADER_UNIFORM" -> Option.SHADER_UNIFORM;
                    case "TURBOISM_MATRIX_SCRATCH" -> Option.MATRIX_SCRATCH;
                    case "TURBOISM_IMAGE_ARCHIVE_REUSE" -> Option.IMAGE_ARCHIVE_REUSE;
                    case "TURBOISM_FLOAT_ARRAY_PARSE_CACHE" -> Option.FLOAT_ARRAY_PARSE_CACHE;
                    case "TURBOISM_TEXTURE_UPLOAD_PREPARATION" -> Option.TEXTURE_UPLOAD_PREPARATION;
                    case "TURBOISM_WARP_POSITION_PROJECTION" -> Option.WARP_POSITION_PROJECTION;
                    default -> null;
                };
        if (option == null) return;
        final Status previous = STATUSES.get(option);
        if ("pending".equals(state) && previous != null && "installed".equals(previous.state())) {
            return;
        }
        STATUSES.put(
                option,
                new Status(
                        previous == null ? null : previous.choice(),
                        state,
                        previous != null
                                        && previous.choice() != null
                                        && !previous.choice().enabled()
                                        && !"installed".equals(state)
                                ? "preference-disabled"
                                : reason));
    }

    /** Detached startup diagnostics; missing entries mean no proven installation attempt. */
    public static synchronized Map<Option, Status> snapshot() {
        return Map.copyOf(STATUSES);
    }
}
