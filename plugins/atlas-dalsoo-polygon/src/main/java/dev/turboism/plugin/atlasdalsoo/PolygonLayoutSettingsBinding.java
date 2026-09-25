package dev.turboism.plugin.atlasdalsoo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigDocument;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigRegistrationException;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutBackend;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutQuality;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;

/**
 * Typed persistence for the polygon-layout policy.
 *
 * <p>Per-item policies are stored as a bounded string list of
 * {@code modelId/textureId|participate|preserveAngle|preserveScale|preservePosition}
 * entries - Cubism 5.2/5.3 cannot persist AutoLayoutLock-style flags in the model,
 * so Turboism keeps them in its own project/plugin configuration.</p>
 *
 * <p>Automatic-scale tuning mirrors the Cubism 5.4 {@code AUTO_SCALE_TOLERANCE}
 * and {@code AUTO_SCALE_MAX_TRY} controls: the tolerance is stored in per-mille
 * (0 falls back to the built-in default) and {@code auto-scale-max-try} 0 derives
 * the attempt bound from the quality preset.</p>
 */
final class PolygonLayoutSettingsBinding {

    static final String CONFIG_ID = "texture-atlas-dalsoo.layout";
    static final String CONFIG_PATH = "texture-atlas-dalsoo/layout.cfg";
    private static final int MAX_POLICY_ENTRIES = 4096;
    private static final int MAX_POLICY_ENTRY_LENGTH = 256;
    private static final int MAX_AUTO_SCALE_TRY = 64;

    private static final ConfigKey<TextureAtlasLayoutBackend> BACKEND = new ConfigKey<>(
        CONFIG_ID, "backend", TextureAtlasLayoutBackend.AUTO,
        ConfigCodecs.enumValue(TextureAtlasLayoutBackend.class));
    private static final ConfigKey<TextureAtlasRotationMode> ROTATION = new ConfigKey<>(
        CONFIG_ID, "rotation", TextureAtlasRotationMode.QUARTER,
        ConfigCodecs.enumValue(TextureAtlasRotationMode.class));
    private static final ConfigKey<TextureAtlasLayoutQuality> QUALITY = new ConfigKey<>(
        CONFIG_ID, "quality", TextureAtlasLayoutQuality.BALANCED,
        ConfigCodecs.enumValue(TextureAtlasLayoutQuality.class));
    private static final ConfigKey<Boolean> AUTO_SCALE = new ConfigKey<>(
        CONFIG_ID, "auto-scale", true, ConfigCodecs.booleanValue());
    private static final ConfigKey<Integer> FIXED_SCALE_PERCENT = new ConfigKey<>(
        CONFIG_ID, "fixed-scale-percent", 100,
        ConfigCodecs.boundedInt(1, 800));
    private static final ConfigKey<Boolean> USE_ABEY = new ConfigKey<>(
        CONFIG_ID, "use-abey", true, ConfigCodecs.booleanValue());
    private static final ConfigKey<Boolean> PARALLEL = new ConfigKey<>(
        CONFIG_ID, "parallel", false, ConfigCodecs.booleanValue());
    private static final ConfigKey<PolygonLayoutLockPreset> LOCK_PRESET = new ConfigKey<>(
        CONFIG_ID, "lock-preset", PolygonLayoutLockPreset.NONE,
        ConfigCodecs.enumValue(PolygonLayoutLockPreset.class));
    private static final ConfigKey<Integer> AUTO_SCALE_TOLERANCE_PERMILLE = new ConfigKey<>(
        CONFIG_ID, "auto-scale-tolerance-permille", 0,
        ConfigCodecs.boundedInt(0, 1000));
    private static final ConfigKey<Integer> AUTO_SCALE_MAX_TRY = new ConfigKey<>(
        CONFIG_ID, "auto-scale-max-try", 0,
        ConfigCodecs.boundedInt(0, MAX_AUTO_SCALE_TRY));
    private static final ConfigKey<List<String>> ITEM_POLICIES = new ConfigKey<>(
        CONFIG_ID, "item-policies", List.of(),
        ConfigCodecs.boundedStringList(MAX_POLICY_ENTRIES, MAX_POLICY_ENTRY_LENGTH));
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
                input.encodedValues() == null ? Map.of() : input.encodedValues());
            values.putIfAbsent(LOCK_PRESET.name(),
                PolygonLayoutLockPreset.NONE.name());
            values.putIfAbsent(AUTO_SCALE_TOLERANCE_PERMILLE.name(), "0");
            values.putIfAbsent(AUTO_SCALE_MAX_TRY.name(), "0");
            return new ConfigDocument(2, values);
        }
    };
    private static final ConfigSchema SCHEMA = new ConfigSchema(
        CONFIG_ID, CONFIG_PATH, 2,
        List.of(BACKEND, ROTATION, QUALITY, AUTO_SCALE, FIXED_SCALE_PERCENT,
            USE_ABEY, PARALLEL, LOCK_PRESET, AUTO_SCALE_TOLERANCE_PERMILLE,
            AUTO_SCALE_MAX_TRY, ITEM_POLICIES));

    private PluginConfigRegistry registry;
    private volatile PolygonLayoutSettings confirmed = PolygonLayoutSettings.defaults();
    private long revision;
    private long epoch;
    private boolean initialized;
    private boolean enabled;

    CompletionStage<Boolean> init(final PluginConfigRegistry value) {
        registry = Objects.requireNonNull(value, "value");
        try {
            return registry.registerSchema(SCHEMA, List.of(V1_TO_V2))
                .handle((ignored, failure) -> {
                    initialized = failure == null;
                    return initialized;
                });
        } catch (ConfigRegistrationException | UnsupportedOperationException failure) {
            return CompletableFuture.completedStage(false);
        }
    }

    CompletionStage<Boolean> enable() {
        if (!initialized || registry == null) {
            return CompletableFuture.completedStage(false);
        }
        enabled = true;
        final long active = ++epoch;
        try {
            final var backendR = registry.read(BACKEND).toCompletableFuture();
            final var rotationR = registry.read(ROTATION).toCompletableFuture();
            final var qualityR = registry.read(QUALITY).toCompletableFuture();
            final var autoScaleR = registry.read(AUTO_SCALE).toCompletableFuture();
            final var scalePctR = registry.read(FIXED_SCALE_PERCENT).toCompletableFuture();
            final var abeyR = registry.read(USE_ABEY).toCompletableFuture();
            final var parallelR = registry.read(PARALLEL).toCompletableFuture();
            final var lockPresetR = registry.read(LOCK_PRESET).toCompletableFuture();
            final var toleranceR = registry.read(AUTO_SCALE_TOLERANCE_PERMILLE)
                .toCompletableFuture();
            final var maxTryR = registry.read(AUTO_SCALE_MAX_TRY).toCompletableFuture();
            final var policiesR = registry.read(ITEM_POLICIES).toCompletableFuture();
            return CompletableFuture.allOf(backendR, rotationR, qualityR, autoScaleR,
                scalePctR, abeyR, parallelR, lockPresetR, toleranceR, maxTryR,
                policiesR).handle((ignored, failure) -> {
                if (failure != null || !enabled || epoch != active) {
                    return false;
                }
                final var backend = backendR.join();
                final var rotation = rotationR.join();
                final var quality = qualityR.join();
                final var autoScale = autoScaleR.join();
                final var scalePct = scalePctR.join();
                final var abey = abeyR.join();
                final var parallel = parallelR.join();
                final var lockPreset = lockPresetR.join();
                final var tolerance = toleranceR.join();
                final var maxTry = maxTryR.join();
                final var policies = policiesR.join();
                if (backend.error().isPresent() || rotation.error().isPresent()
                    || quality.error().isPresent() || autoScale.error().isPresent()
                    || scalePct.error().isPresent() || abey.error().isPresent()
                    || parallel.error().isPresent() || lockPreset.error().isPresent()
                    || tolerance.error().isPresent() || maxTry.error().isPresent()
                    || policies.error().isPresent()) {
                    return false;
                }
                confirmed = new PolygonLayoutSettings(
                    backend.value().value(), rotation.value().value(),
                    quality.value().value(), autoScale.value().value(),
                    scalePct.value().value() / 100.0, abey.value().value(),
                    parallel.value().value(), lockPreset.value().value(),
                    tolerance(tolerance.value().value()), maxTry.value().value(),
                    decodePolicies(policies.value().value()));
                revision = Math.max(backend.value().revision(), policies.value().revision());
                return true;
            });
        } catch (UnsupportedOperationException failure) {
            return CompletableFuture.completedStage(false);
        }
    }

    /**
     * Persists a dialog- or API-confirmed policy. Writes are revision-chained in
     * schema order; any conflict aborts without touching {@code confirmed}.
     */
    CompletionStage<Boolean> update(final PolygonLayoutSettings value) {
        Objects.requireNonNull(value, "value");
        if (!enabled || registry == null) {
            return CompletableFuture.completedStage(false);
        }
        if (value.equals(confirmed)) {
            return CompletableFuture.completedStage(true);
        }
        final long active = epoch;
        try {
            final List<KeyWrite<?>> writes = List.of(
                new KeyWrite<>(BACKEND, value.backend()),
                new KeyWrite<>(ROTATION, value.rotation()),
                new KeyWrite<>(QUALITY, value.quality()),
                new KeyWrite<>(AUTO_SCALE, value.automaticScale()),
                new KeyWrite<>(FIXED_SCALE_PERCENT,
                    (int) Math.round(value.fixedScale() * 100)),
                new KeyWrite<>(USE_ABEY, value.useAbey()),
                new KeyWrite<>(PARALLEL, value.parallel()),
                new KeyWrite<>(LOCK_PRESET, value.lockPreset()),
                new KeyWrite<>(AUTO_SCALE_TOLERANCE_PERMILLE,
                    (int) Math.round(value.autoScaleTolerance() * 1000)),
                new KeyWrite<>(AUTO_SCALE_MAX_TRY, value.autoScaleMaxTry()),
                new KeyWrite<>(ITEM_POLICIES, encodePolicies(value.itemPolicies())));
            CompletionStage<ConfigWriteResult> chain =
                CompletableFuture.completedFuture(null);
            for (final KeyWrite<?> write : writes) {
                chain = chain.thenCompose(previous -> {
                    final long expected = previous == null ? revision
                        : previous.revision();
                    return write.run(registry, expected);
                });
            }
            return chain.handle((result, failure) -> {
                if (failure != null || !enabled || epoch != active
                    || result == null || !result.written()) {
                    return false;
                }
                confirmed = value;
                revision = result.revision();
                return true;
            });
        } catch (UnsupportedOperationException failure) {
            return CompletableFuture.completedStage(false);
        }
    }

    PolygonLayoutSettings confirmed() {
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

    /** Stored per-mille tolerance; 0 keeps the built-in default. */
    private static double tolerance(final int permille) {
        return permille <= 0
            ? PolygonLayoutSettings.DEFAULT_AUTO_SCALE_TOLERANCE
            : permille / 1000.0;
    }

    private record KeyWrite<T>(ConfigKey<T> key, T value) {
        private CompletionStage<ConfigWriteResult> run(
            final PluginConfigRegistry registry, final long expectedRevision) {
            return registry.write(key, value, expectedRevision);
        }
    }

    private static Map<String, TextureAtlasItemLayoutPolicy> decodePolicies(
        final List<String> entries) {
        final Map<String, TextureAtlasItemLayoutPolicy> out = new LinkedHashMap<>();
        if (entries == null) {
            return out;
        }
        for (final String entry : entries) {
            if (entry == null) {
                continue;
            }
            final int sep = entry.indexOf('|');
            if (sep <= 0) {
                continue;
            }
            final String key = entry.substring(0, sep);
            final int slash = key.lastIndexOf('/');
            if (slash <= 0 || slash == key.length() - 1) {
                continue;
            }
            final String textureId = key.substring(slash + 1);
            final String[] flags = entry.substring(sep + 1).split("\\|", -1);
            try {
                out.put(key, new TextureAtlasItemLayoutPolicy(textureId,
                    flag(flags, 0, true), flag(flags, 1, false),
                    flag(flags, 2, false), flag(flags, 3, false)));
            } catch (IllegalArgumentException ignored) {
                // skip malformed entries
            }
        }
        return out;
    }

    private static List<String> encodePolicies(
        final Map<String, TextureAtlasItemLayoutPolicy> policies) {
        final List<String> out = new java.util.ArrayList<>(policies.size());
        for (final Map.Entry<String, TextureAtlasItemLayoutPolicy> entry
            : policies.entrySet()) {
            final TextureAtlasItemLayoutPolicy policy = entry.getValue();
            out.add(entry.getKey()
                + "|" + (policy.participate() ? "1" : "0")
                + "|" + (policy.preserveAngle() ? "1" : "0")
                + "|" + (policy.preserveScale() ? "1" : "0")
                + "|" + (policy.preservePosition() ? "1" : "0"));
        }
        return List.copyOf(out);
    }

    private static boolean flag(final String[] flags, final int index,
        final boolean fallback) {
        if (index >= flags.length) {
            return fallback;
        }
        return "1".equals(flags[index]) || "true".equals(flags[index]);
    }
}
