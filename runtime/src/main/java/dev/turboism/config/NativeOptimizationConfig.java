package dev.turboism.config;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;

/** Immutable launch snapshot of native optimization preferences from the global configuration. */
public final class NativeOptimizationConfig {
    /** Optimization identity shared by startup policy, persistence and diagnostics. */
    public enum Option {
        MODEL_UPDATE_SKIP("modelUpdateSkip"),
        INCREMENTAL_UPDATE("incrementalUpdate"),
        UNIFORM_LOCATION_CACHE("uniformLocationCache"),
        UPLOAD_ELISION("uploadElision"),
        INPUT_PATH_ELISION("inputPathElision"),
        MESA_GL_THREAD("mesaGlThread"),
        MESH_INTERPOLATION("meshInterpolation"),
        SHADER_UNIFORM("shaderUniform"),
        MATRIX_SCRATCH("matrixScratch"),
        IMAGE_ARCHIVE_REUSE("imageArchiveReuse"),
        FLOAT_ARRAY_PARSE_CACHE("floatArrayParseCache"),
        TEXTURE_UPLOAD_PREPARATION("textureUploadPreparation"),
        WARP_POSITION_PROJECTION("warpPositionProjection"),
        MESH_TRIANGULATION_HASH("meshTriangulationHashFix"),
        TRIANGULATION_EDGE_INDEX("meshTriangulationEdgeIndex"),
        ATLAS_TILE_BBOX("atlasTileBbox"),
        ATLAS_CACHE_REUSE("atlasCacheReuse");

        private final PerformanceSettingsConfig.Field field;

        Option(String key) {
            field = PerformanceSettingsConfig.Field.forKey(key);
        }
        /** Global field definition, including its existing JSON location. */
        public PerformanceSettingsConfig.Field field() {
            return field;
        }
        /** Global field name. */
        public String key() {
            return field.key();
        }
        /** Explicit diagnostic override. */
        public String property() {
            return "turboism.optimization." + key();
        }
        /** Shared platform default. */
        public boolean defaultEnabled(boolean proton) {
            return Boolean.parseBoolean(field.defaultValue(proton));
        }

        /** Resolves one supported global configuration field. */
        public static Option forKey(String key) {
            for (Option option : values()) if (option.key().equals(key)) return option;
            throw new IllegalArgumentException("Unknown native optimization: " + key);
        }
    }

    /** Resolved startup preference, including the source of an explicit override. */
    public record Choice(boolean enabled, String source) {}

    private final PerformanceSettingsConfig settings;

    private NativeOptimizationConfig(PerformanceSettingsConfig settings) {
        this.settings = settings;
    }
    /** Captures every performance preference once for this launch attempt. */
    public static NativeOptimizationConfig load(Path home, Consumer<String> diagnostic) {
        return new NativeOptimizationConfig(PerformanceSettingsConfig.load(home, diagnostic));
    }
    /** The complete Performance page snapshot used by this launch. */
    public PerformanceSettingsConfig settings() {
        return settings;
    }
    /** Immutable native optimization request. */
    public Choice choice(Option option) {
        var value = settings.value(Objects.requireNonNull(option).field());
        return new Choice(Boolean.parseBoolean(value.value()), value.source());
    }
}
