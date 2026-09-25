package dev.turboism.plugin.atlasdalsoo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigMigration;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigValue;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutService;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonLayoutSnapshot;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasPolygonPlan;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasRotationMode;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.Registration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dialog bridge state: publish seeds the runtime properties from persisted
 * settings and sync merges dialog edits back into the confirmed policy.
 */
class TextureAtlasDalsooPluginDialogStateTest {

    private static final String[] BRIDGE_KEYS = {
        TextureAtlasDalsooPlugin.DIALOG_ALGORITHM_KEY,
        TextureAtlasDalsooPlugin.DIALOG_PARALLEL_KEY,
        TextureAtlasDalsooPlugin.DIALOG_ROTATION_KEY,
        TextureAtlasDalsooPlugin.DIALOG_LOCK_PRESET_KEY,
        TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_KEY,
        TextureAtlasDalsooPlugin.DIALOG_FIXED_SCALE_PERCENT_KEY,
        TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_TOLERANCE_KEY,
        TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_MAX_TRY_KEY,
        TextureAtlasDalsooPlugin.DIALOG_KERNEL_KEY,
        TextureAtlasDalsooPlugin.NATIVE_AUTO_LAYOUT_CALLBACK_KEY
    };

    @AfterEach
    void clearBridge() {
        for (final String key : BRIDGE_KEYS) {
            System.getProperties().remove(key);
        }
    }

    @Test
    void publishSeedsAllOptionProperties() {
        final var context = new ShellPluginContext();
        final var plugin = new TextureAtlasDalsooPlugin();
        plugin.init(context);
        plugin.enable();
        try {
            assertEquals("dalsoo",
                System.getProperty(TextureAtlasDalsooPlugin.DIALOG_ALGORITHM_KEY));
            assertEquals("QUARTER",
                System.getProperty(TextureAtlasDalsooPlugin.DIALOG_ROTATION_KEY));
            assertEquals("NONE",
                System.getProperty(TextureAtlasDalsooPlugin.DIALOG_LOCK_PRESET_KEY));
            assertEquals("true",
                System.getProperty(TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_KEY));
            assertEquals("100",
                System.getProperty(
                    TextureAtlasDalsooPlugin.DIALOG_FIXED_SCALE_PERCENT_KEY));
            assertEquals("5",
                System.getProperty(
                    TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_TOLERANCE_KEY));
            assertEquals("0",
                System.getProperty(
                    TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_MAX_TRY_KEY));
            assertEquals("abey",
                System.getProperty(TextureAtlasDalsooPlugin.DIALOG_KERNEL_KEY));
            assertEquals("false",
                System.getProperty(TextureAtlasDalsooPlugin.DIALOG_PARALLEL_KEY));
        } finally {
            plugin.shutdown();
        }
    }

    @Test
    void syncPersistsDialogValuesIntoConfirmedSettings() {
        final var context = new ShellPluginContext();
        final var plugin = new TextureAtlasDalsooPlugin();
        plugin.init(context);
        plugin.enable();
        try {
            System.getProperties().put(
                TextureAtlasDalsooPlugin.DIALOG_ROTATION_KEY, "FREE");
            System.getProperties().put(
                TextureAtlasDalsooPlugin.DIALOG_LOCK_PRESET_KEY, "ANGLE_SCALE");
            System.getProperties().put(
                TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_KEY, "false");
            System.getProperties().put(
                TextureAtlasDalsooPlugin.DIALOG_FIXED_SCALE_PERCENT_KEY, "150");
            System.getProperties().put(
                TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_TOLERANCE_KEY, "20");
            System.getProperties().put(
                TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_MAX_TRY_KEY, "7");
            System.getProperties().put(
                TextureAtlasDalsooPlugin.DIALOG_KERNEL_KEY, "dalalah");
            System.getProperties().put(
                TextureAtlasDalsooPlugin.DIALOG_PARALLEL_KEY, "true");

            invokeNativeCallback();

            final PolygonLayoutSettings confirmed = plugin.confirmedSettings();
            assertEquals(TextureAtlasRotationMode.FREE, confirmed.rotation());
            assertEquals(PolygonLayoutLockPreset.ANGLE_SCALE,
                confirmed.lockPreset());
            assertFalse(confirmed.automaticScale());
            assertEquals(1.5, confirmed.fixedScale(), 1e-9);
            assertEquals(0.02, confirmed.autoScaleTolerance(), 1e-9);
            assertEquals(7, confirmed.autoScaleMaxTry());
            assertFalse(confirmed.useAbey());
            assertTrue(confirmed.parallel());

            // persisted: a fresh binding over the same registry reloads them
            final var reloaded = new PolygonLayoutSettingsBinding();
            reloaded.init(context.config).toCompletableFuture().join();
            assertTrue(reloaded.enable().toCompletableFuture().join());
            assertEquals(confirmed, reloaded.confirmed());
        } finally {
            plugin.shutdown();
        }
    }

    @Test
    void malformedDialogValuesKeepConfirmedSettings() {
        final var context = new ShellPluginContext();
        final var plugin = new TextureAtlasDalsooPlugin();
        plugin.init(context);
        plugin.enable();
        try {
            final PolygonLayoutSettings before = plugin.confirmedSettings();
            System.getProperties().put(
                TextureAtlasDalsooPlugin.DIALOG_ROTATION_KEY, "SIDEWAYS");
            System.getProperties().put(
                TextureAtlasDalsooPlugin.DIALOG_LOCK_PRESET_KEY, "EVERYTHING");
            System.getProperties().put(
                TextureAtlasDalsooPlugin.DIALOG_FIXED_SCALE_PERCENT_KEY, "9999");
            System.getProperties().put(
                TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_MAX_TRY_KEY, "-3");
            System.getProperties().put(
                TextureAtlasDalsooPlugin.DIALOG_KERNEL_KEY, "bogus");

            invokeNativeCallback();

            assertEquals(before, plugin.confirmedSettings());
        } finally {
            plugin.shutdown();
        }
    }

    @Test
    void otherAlgorithmSelectionDoesNotSyncDalsooSettings() {
        final var context = new ShellPluginContext();
        final var plugin = new TextureAtlasDalsooPlugin();
        plugin.init(context);
        plugin.enable();
        try {
            final PolygonLayoutSettings before = plugin.confirmedSettings();
            System.getProperties().put(
                TextureAtlasDalsooPlugin.DIALOG_ALGORITHM_KEY, "other");
            System.getProperties().put(
                TextureAtlasDalsooPlugin.DIALOG_ROTATION_KEY, "FREE");

            invokeNativeCallback();

            assertEquals(before, plugin.confirmedSettings());
        } finally {
            plugin.shutdown();
        }
    }

    private static void invokeNativeCallback() {
        final Object callback = System.getProperties().get(
            TextureAtlasDalsooPlugin.NATIVE_AUTO_LAYOUT_CALLBACK_KEY);
        assertTrue(callback instanceof BooleanSupplier);
        ((BooleanSupplier) callback).getAsBoolean();
    }

    private static final class ShellPluginContext implements PluginContext {
        private final List<String> infoMessages = new ArrayList<>();
        private final PluginLogger logger = new PluginLogger() {
            @Override public void debug(final String message) { }
            @Override public void info(final String message) {
                infoMessages.add(message);
            }
            @Override public void warn(final String message) { }
            @Override public void error(final String message) { }
            @Override public void error(final String message,
                final Throwable throwable) { }
        };
        private final FakeRegistry config = new FakeRegistry();
        private final TextureAtlasLayoutAlgorithmRegistry algorithms =
            new TestAlgorithmRegistry();
        private final DisposableScope scope = new DisposableScope();

        @Override public PluginDescriptor descriptor() { throw unused(); }
        @Override public PluginLogger logger() { return logger; }
        @Override public PluginPaths paths() { throw unused(); }
        @Override public PluginConfigRegistry config() { return config; }
        @Override public DisposableScope disposableScope() { return scope; }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public dev.turboism.sdk.event.EventBus eventBus() {
            throw unused();
        }
        @Override public dev.turboism.sdk.action.ActionRegistry actions() {
            throw unused();
        }
        @Override public dev.turboism.sdk.menu.MenuRegistry menus() {
            throw unused();
        }
        @Override public dev.turboism.sdk.ui.UiScheduler uiScheduler() {
            throw unused();
        }
        @Override public dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() {
            throw unused();
        }

        @Override public PluginLocalization localization() {
            return new PluginLocalization() {
                @Override public Locale locale() { return Locale.ENGLISH; }
                @Override public String text(final String key) { return key; }
                @Override public String format(final String key,
                    final Object... arguments) {
                    return key;
                }
                @Override public boolean contains(final String key) {
                    return true;
                }
            };
        }

        @Override public CubismFacade cubism() {
            return new CubismFacade() {
                @Override public dev.turboism.sdk.cubism.CubismRuntimeSnapshot
                    runtime() { throw unused(); }
                @Override public Optional<dev.turboism.sdk.cubism.ProjectSnapshot>
                    activeProject() { return Optional.empty(); }
                @Override public Optional<dev.turboism.sdk.cubism.DocumentSnapshot>
                    activeDocument() { return Optional.empty(); }
                @Override public Optional<dev.turboism.sdk.cubism.ModelSnapshot>
                    activeModel() { return Optional.empty(); }
                @Override public boolean isHostPresent() { return false; }
                @Override public dev.turboism.sdk.cubism.transaction
                    .TransactionManager transactionManager() { throw unused(); }
                @Override public TextureAtlasLayoutService textureAtlasLayouts() {
                    return new TextureAtlasLayoutService() {
                        @Override public Optional<dev.turboism.sdk.cubism
                            .textureatlas.TextureAtlasLayoutSnapshot> current() {
                            return Optional.empty();
                        }
                        @Override public TextureAtlasLayoutApplyResult apply(
                            final dev.turboism.sdk.cubism.textureatlas
                                .TextureAtlasLayoutTarget target,
                            final dev.turboism.sdk.cubism.textureatlas
                                .TextureAtlasLayoutPlan plan) {
                            return TextureAtlasLayoutApplyResult.failed(
                                TextureAtlasLayoutFailureCode.PLAN_INVALID,
                                "no snapshot");
                        }
                    };
                }
                @Override public TextureAtlasPolygonLayoutService
                    textureAtlasPolygonLayouts() {
                    return new TextureAtlasPolygonLayoutService() {
                        @Override public Optional<TextureAtlasPolygonLayoutSnapshot>
                            currentPolygon() {
                            return Optional.empty();
                        }
                        @Override public TextureAtlasLayoutApplyResult apply(
                            final dev.turboism.sdk.cubism.textureatlas
                                .TextureAtlasLayoutTarget target,
                            final TextureAtlasPolygonPlan plan) {
                            return TextureAtlasLayoutApplyResult.failed(
                                TextureAtlasLayoutFailureCode.PLAN_INVALID,
                                "no snapshot");
                        }
                    };
                }
                @Override public TextureAtlasLayoutAlgorithmRegistry
                    textureAtlasAlgorithms() {
                    return algorithms;
                }
            };
        }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class TestAlgorithmRegistry
        implements TextureAtlasLayoutAlgorithmRegistry {
        private final Map<String, TextureAtlasLayoutAlgorithm> algorithms =
            new LinkedHashMap<>();

        @Override public Registration register(
            final TextureAtlasLayoutAlgorithm algorithm) {
            algorithms.put(algorithm.id(), algorithm);
            return () -> algorithms.remove(algorithm.id(), algorithm);
        }

        @Override public Optional<TextureAtlasLayoutAlgorithm> find(
            final String id) {
            return Optional.ofNullable(algorithms.get(id));
        }

        @Override public List<TextureAtlasLayoutAlgorithm> algorithms() {
            return List.copyOf(algorithms.values());
        }
    }

    private static final class FakeRegistry implements PluginConfigRegistry {
        private final Map<ConfigKey<?>, Object> values = new HashMap<>();
        private long revision;

        @Override public CompletionStage<Void> registerSchema(
            final ConfigSchema schema, final List<ConfigMigration> migrations) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> CompletionStage<ConfigReadResult<T>> read(
            final ConfigKey<T> key) {
            final boolean stored = values.containsKey(key);
            final T current = stored ? (T) values.get(key) : key.defaultValue();
            final ConfigValueSource source =
                stored ? ConfigValueSource.STORED : ConfigValueSource.DEFAULT_MISSING;
            return CompletableFuture.completedFuture(new ConfigReadResult<>(
                new ConfigValue<>(current, source, revision), Optional.empty()));
        }

        @Override public <T> CompletionStage<ConfigWriteResult> write(
            final ConfigKey<T> key, final T value, final long expectedRevision) {
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
