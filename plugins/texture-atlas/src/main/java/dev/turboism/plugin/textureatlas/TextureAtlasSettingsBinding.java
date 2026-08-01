package dev.turboism.plugin.textureatlas;

import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigDocument;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigRegistrationException;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.PluginConfigRegistry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Typed persistence for the plugin-owned layout policy. */
final class TextureAtlasSettingsBinding {
    static final String CONFIG_ID = "texture-atlas.layout";
    static final String CONFIG_PATH = "texture-atlas/layout.cfg";
    private static final ConfigKey<TextureAtlasLayoutMode> MODE = new ConfigKey<>(
        CONFIG_ID,
        "layout-mode",
        TextureAtlasLayoutMode.PART_BUCKET,
        ConfigCodecs.enumValue(TextureAtlasLayoutMode.class)
    );
    private static final ConfigKey<String> ALGORITHM = new ConfigKey<>(
        CONFIG_ID,
        "algorithm",
        TextureAtlasPlugin.ALGORITHM_MAXRECTS,
        ConfigCodecs.stringValue(64)
    );
    private static final ConfigKey<Boolean> PARALLEL = new ConfigKey<>(
        CONFIG_ID,
        "parallel",
        false,
        ConfigCodecs.booleanValue()
    );
    private static final ConfigMigration V1_TO_V2 = new ConfigMigration() {
        @Override
        public int fromVersion() {
            return 1;
        }

        @Override
        public int toVersion() {
            return 2;
        }

        @Override
        public ConfigDocument migrate(final ConfigDocument input) {
            final Map<String, String> values = new LinkedHashMap<>(
                input.encodedValues() == null ? Map.of() : input.encodedValues()
            );
            values.putIfAbsent(ALGORITHM.name(), TextureAtlasPlugin.ALGORITHM_MAXRECTS);
            values.putIfAbsent(PARALLEL.name(), "false");
            return new ConfigDocument(2, values);
        }
    };
    private static final ConfigMigration V2_TO_V3 = new ConfigMigration() {
        @Override
        public int fromVersion() {
            return 2;
        }

        @Override
        public int toVersion() {
            return 3;
        }

        @Override
        public ConfigDocument migrate(final ConfigDocument input) {
            final java.util.Map<String, String> values = new java.util.LinkedHashMap<>(
                input.encodedValues() == null ? java.util.Map.of() : input.encodedValues()
            );
            // v2 stored the enum name; v3 stores the algorithm id
            values.computeIfPresent("algorithm", (key, value) ->
                "NATIVE".equals(value) ? TextureAtlasPlugin.ALGORITHM_NATIVE
                    : "MAXRECTS".equals(value) ? TextureAtlasPlugin.ALGORITHM_MAXRECTS
                    : value);
            return new ConfigDocument(3, values);
        }
    };
    private static final ConfigSchema SCHEMA = new ConfigSchema(
        CONFIG_ID,
        CONFIG_PATH,
        3,
        List.of(MODE, ALGORITHM, PARALLEL)
    );

    private PluginConfigRegistry registry;
    private TextureAtlasSettings confirmed = TextureAtlasSettings.defaults();
    private long revision;
    private long epoch;
    private boolean initialized;
    private boolean enabled;

    CompletionStage<Boolean> init(final PluginConfigRegistry value) {
        registry = Objects.requireNonNull(value, "value");
        try {
            return registry.registerSchema(SCHEMA, List.of(V1_TO_V2, V2_TO_V3)).handle((ignored, failure) -> {
                initialized = failure == null;
                return initialized;
            });
        } catch (ConfigRegistrationException | UnsupportedOperationException failure) {
            return CompletableFuture.completedStage(false);
        }
    }

    CompletionStage<Boolean> enable() {
        if (!initialized || registry == null) return CompletableFuture.completedStage(false);
        enabled = true;
        final long active = ++epoch;
        try {
            return registry.read(MODE).thenCompose(modeRead ->
                registry.read(ALGORITHM).thenCompose(algorithmRead ->
                    registry.read(PARALLEL).thenApply(parallelRead -> {
                        if (modeRead.error().isPresent()
                            || algorithmRead.error().isPresent()
                            || parallelRead.error().isPresent()) {
                            return false;
                        }
                        confirmed = new TextureAtlasSettings(
                            modeRead.value().value(),
                            algorithmRead.value().value(),
                            parallelRead.value().value()
                        );
                        revision = modeRead.value().revision();
                        return true;
                    })
                )
            ).handle((ignored, failure) -> {
                if (failure != null || !enabled || epoch != active) return false;
                return Boolean.TRUE.equals(ignored);
            });
        } catch (UnsupportedOperationException failure) {
            return CompletableFuture.completedStage(false);
        }
    }

    CompletionStage<Boolean> update(final TextureAtlasSettings value) {
        Objects.requireNonNull(value, "value");
        if (!enabled || registry == null) return CompletableFuture.completedStage(false);
        if (value.equals(confirmed)) return CompletableFuture.completedStage(true);
        final long active = epoch;
        try {
            return registry.write(MODE, value.layoutMode(), revision).thenCompose(modeWrite -> {
                if (!modeWrite.written()) return CompletableFuture.completedStage(false);
                return registry.write(ALGORITHM, value.algorithmId(), modeWrite.revision())
                    .thenCompose(algorithmWrite -> {
                        if (!algorithmWrite.written()) return CompletableFuture.completedStage(false);
                        return registry.write(PARALLEL, value.parallel(), algorithmWrite.revision())
                            .thenApply(parallelWrite -> {
                                if (!parallelWrite.written()) return false;
                                confirmed = value;
                                revision = parallelWrite.revision();
                                return true;
                            });
                    });
            }).handle((written, failure) -> {
                if (failure != null || !enabled || epoch != active) return false;
                return Boolean.TRUE.equals(written);
            });
        } catch (UnsupportedOperationException failure) {
            return CompletableFuture.completedStage(false);
        }
    }

    TextureAtlasSettings confirmed() {
        return confirmed;
    }

    void disable() {
        enabled = false;
        epoch++;
    }

    void shutdown() {
        disable();
        initialized = false;
        registry = null;
    }
}
