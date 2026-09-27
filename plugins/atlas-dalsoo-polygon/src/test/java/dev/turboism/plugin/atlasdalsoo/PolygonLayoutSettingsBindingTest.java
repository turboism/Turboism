package dev.turboism.plugin.atlasdalsoo;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigValue;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasItemLayoutPolicy;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutBackend;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutQuality;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;
import dev.turboism.sdk.plugin.Registration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolygonLayoutSettingsBindingTest {

    @Test
    void defaultsLoadCleanly() {
        final var registry = new FakeRegistry();
        final var binding = new PolygonLayoutSettingsBinding();
        assertTrue(binding.init(registry).toCompletableFuture().join());
        assertTrue(binding.enable().toCompletableFuture().join());
        final PolygonLayoutSettings confirmed = binding.confirmed();
        assertEquals(PolygonLayoutLockPreset.NONE, confirmed.lockPreset());
        assertEquals(PolygonLayoutSettings.DEFAULT_AUTO_SCALE_TOLERANCE,
            confirmed.autoScaleTolerance(), 1e-9);
        assertEquals(PolygonLayoutSettings.AUTO_SCALE_MAX_TRY_QUALITY,
            confirmed.autoScaleMaxTry());
        assertEquals(TextureAtlasRotationMode.QUARTER, confirmed.rotation());
        assertEquals(2, registry.schema.version());
    }

    @Test
    void updatePersistsAndSurvivesReload() {
        final var registry = new FakeRegistry();
        var binding = new PolygonLayoutSettingsBinding();
        binding.init(registry).toCompletableFuture().join();
        binding.enable().toCompletableFuture().join();

        final var policy = new PolygonLayoutSettings(
            TextureAtlasLayoutBackend.DALSOO_POLYGON,
            TextureAtlasRotationMode.FREE, TextureAtlasLayoutQuality.DENSE,
            false, 1.5, false, true,
            PolygonLayoutLockPreset.ANGLE_SCALE, 0.02, 7,
            Map.of("m/t", new TextureAtlasItemLayoutPolicy("t",
                true, true, false, true)));
        assertTrue(binding.update(policy).toCompletableFuture().join());
        assertEquals(policy, binding.confirmed());
        binding.disable();

        binding = new PolygonLayoutSettingsBinding();
        binding.init(registry).toCompletableFuture().join();
        assertTrue(binding.enable().toCompletableFuture().join());
        assertEquals(TextureAtlasRotationMode.FREE, binding.confirmed().rotation());
        assertEquals(PolygonLayoutLockPreset.ANGLE_SCALE,
            binding.confirmed().lockPreset());
        assertEquals(0.02, binding.confirmed().autoScaleTolerance(), 1e-9);
        assertEquals(7, binding.confirmed().autoScaleMaxTry());
        assertTrue(binding.confirmed().itemPolicies().containsKey("m/t"));
        assertEquals(1.5, binding.confirmed().fixedScale(), 1e-9);
    }

    @Test
    void globalPresetSuppliesDefaultPolicyAndItemPolicyWins() {
        final var preset = PolygonLayoutLockPreset.POS_ANGLE.toPolicy("tex");
        assertTrue(preset.preservePosition());
        assertTrue(preset.preserveAngle());
        assertTrue(!preset.preserveScale());
        assertTrue(preset.participate());

        final var custom = new TextureAtlasItemLayoutPolicy("tex",
            false, false, false, false);
        final var settings = new PolygonLayoutSettings(
            TextureAtlasLayoutBackend.AUTO, TextureAtlasRotationMode.QUARTER,
            TextureAtlasLayoutQuality.BALANCED, true, 1.0, true, false,
            PolygonLayoutLockPreset.POS_ANGLE,
            PolygonLayoutSettings.DEFAULT_AUTO_SCALE_TOLERANCE, 0,
            Map.of("m/tex", custom));
        assertEquals(custom, settings.policyFor("m", "tex"));
        final var other = settings.policyFor("m", "other");
        assertTrue(other.preservePosition() && other.preserveAngle()
            && !other.preserveScale());
    }

    @Test
    void allLockPresetsMapToNativeFlags() {
        record Flags(boolean p, boolean a, boolean s) { }
        final Map<PolygonLayoutLockPreset, Flags> expected = Map.of(
            PolygonLayoutLockPreset.NONE, new Flags(false, false, false),
            PolygonLayoutLockPreset.ALL, new Flags(true, true, true),
            PolygonLayoutLockPreset.ANGLE, new Flags(false, true, false),
            PolygonLayoutLockPreset.SCALE, new Flags(false, false, true),
            PolygonLayoutLockPreset.ANGLE_SCALE, new Flags(false, true, true),
            PolygonLayoutLockPreset.POS_ANGLE, new Flags(true, true, false));
        for (final var entry : expected.entrySet()) {
            final var policy = entry.getKey().toPolicy("t");
            assertEquals(entry.getValue().p(), policy.preservePosition(),
                entry.getKey() + " position");
            assertEquals(entry.getValue().a(), policy.preserveAngle(),
                entry.getKey() + " angle");
            assertEquals(entry.getValue().s(), policy.preserveScale(),
                entry.getKey() + " scale");
        }
    }

    /** Minimal in-memory registry honouring revision-chained writes. */
    private static final class FakeRegistry implements PluginConfigRegistry {
        private final Map<ConfigKey<?>, Object> values = new HashMap<>();
        private ConfigSchema schema;
        private long revision;

        @Override
        public CompletionStage<Void> registerSchema(final ConfigSchema schema,
            final List<ConfigMigration> migrations) {
            this.schema = schema;
            return CompletableFuture.completedFuture(null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletionStage<ConfigReadResult<T>> read(final ConfigKey<T> key) {
            final boolean stored = values.containsKey(key);
            final T current = stored ? (T) values.get(key) : key.defaultValue();
            final ConfigValueSource source =
                stored ? ConfigValueSource.STORED : ConfigValueSource.DEFAULT_MISSING;
            return CompletableFuture.completedFuture(new ConfigReadResult<>(
                new ConfigValue<>(current, source, revision), Optional.empty()));
        }

        @Override
        public <T> CompletionStage<ConfigWriteResult> write(final ConfigKey<T> key,
            final T value, final long expectedRevision) {
            if (expectedRevision != revision) {
                return CompletableFuture.completedFuture(
                    new ConfigWriteResult(false, revision, Optional.empty()));
            }
            values.put(key, value);
            revision++;
            return CompletableFuture.completedFuture(
                new ConfigWriteResult(true, revision, Optional.empty()));
        }

        @Override public Registration readScope(final String relativePath) {
            return () -> { };
        }

        @Override public Registration writeScope(final String relativePath) {
            return () -> { };
        }

        @Override public Optional<String> readString(final String relativePath,
            final String key) {
            return Optional.empty();
        }

        @Override public void writeString(final String relativePath,
            final String key, final String value) throws PluginConfigException { }
    }
}
