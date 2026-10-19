package dev.turboism.plugin.atlasdalsoo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.test.config.InMemoryConfigRegistry;
import dev.turboism.test.plugin.FakePluginContext;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

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
            assertEquals("dalsoo", System.getProperty(TextureAtlasDalsooPlugin.DIALOG_ALGORITHM_KEY));
            assertEquals("QUARTER", System.getProperty(TextureAtlasDalsooPlugin.DIALOG_ROTATION_KEY));
            assertEquals("NONE", System.getProperty(TextureAtlasDalsooPlugin.DIALOG_LOCK_PRESET_KEY));
            assertEquals("true", System.getProperty(TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_KEY));
            assertEquals("100", System.getProperty(TextureAtlasDalsooPlugin.DIALOG_FIXED_SCALE_PERCENT_KEY));
            assertEquals("5", System.getProperty(TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_TOLERANCE_KEY));
            assertEquals("0", System.getProperty(TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_MAX_TRY_KEY));
            assertEquals("abey", System.getProperty(TextureAtlasDalsooPlugin.DIALOG_KERNEL_KEY));
            assertEquals("false", System.getProperty(TextureAtlasDalsooPlugin.DIALOG_PARALLEL_KEY));
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
            System.getProperties().put(TextureAtlasDalsooPlugin.DIALOG_ROTATION_KEY, "FREE");
            System.getProperties().put(TextureAtlasDalsooPlugin.DIALOG_LOCK_PRESET_KEY, "ANGLE_SCALE");
            System.getProperties().put(TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_KEY, "false");
            System.getProperties().put(TextureAtlasDalsooPlugin.DIALOG_FIXED_SCALE_PERCENT_KEY, "150");
            System.getProperties().put(TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_TOLERANCE_KEY, "20");
            System.getProperties().put(TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_MAX_TRY_KEY, "7");
            System.getProperties().put(TextureAtlasDalsooPlugin.DIALOG_KERNEL_KEY, "dalalah");
            System.getProperties().put(TextureAtlasDalsooPlugin.DIALOG_PARALLEL_KEY, "true");

            invokeNativeCallback();

            final PolygonLayoutSettings confirmed = plugin.confirmedSettings();
            assertEquals(TextureAtlasRotationMode.FREE, confirmed.rotation());
            assertEquals(PolygonLayoutLockPreset.ANGLE_SCALE, confirmed.lockPreset());
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
            System.getProperties().put(TextureAtlasDalsooPlugin.DIALOG_ROTATION_KEY, "SIDEWAYS");
            System.getProperties().put(TextureAtlasDalsooPlugin.DIALOG_LOCK_PRESET_KEY, "EVERYTHING");
            System.getProperties().put(TextureAtlasDalsooPlugin.DIALOG_FIXED_SCALE_PERCENT_KEY, "9999");
            System.getProperties().put(TextureAtlasDalsooPlugin.DIALOG_AUTO_SCALE_MAX_TRY_KEY, "-3");
            System.getProperties().put(TextureAtlasDalsooPlugin.DIALOG_KERNEL_KEY, "bogus");

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
            System.getProperties().put(TextureAtlasDalsooPlugin.DIALOG_ALGORITHM_KEY, "other");
            System.getProperties().put(TextureAtlasDalsooPlugin.DIALOG_ROTATION_KEY, "FREE");

            invokeNativeCallback();

            assertEquals(before, plugin.confirmedSettings());
        } finally {
            plugin.shutdown();
        }
    }

    private static void invokeNativeCallback() {
        final Object callback = System.getProperties().get(TextureAtlasDalsooPlugin.NATIVE_AUTO_LAYOUT_CALLBACK_KEY);
        assertTrue(callback instanceof BooleanSupplier);
        ((BooleanSupplier) callback).getAsBoolean();
    }

    private static final class ShellPluginContext extends FakePluginContext {
        private final List<String> infoMessages = new ArrayList<>();
        private final PluginLogger logger = new PluginLogger() {
            @Override
            public void debug(final String message) {}

            @Override
            public void info(final String message) {
                infoMessages.add(message);
            }

            @Override
            public void warn(final String message) {}

            @Override
            public void error(final String message) {}

            @Override
            public void error(final String message, final Throwable throwable) {}
        };
        private final InMemoryConfigRegistry config = new InMemoryConfigRegistry();
        private final TextureAtlasLayoutAlgorithmRegistry algorithms = new TestAlgorithmRegistry();
        private final DisposableScope scope = new DisposableScope();

        ShellPluginContext() {
            super(FakePluginContext.builder());
        }

        @Override
        public PluginLogger logger() {
            return logger;
        }

        @Override
        public InMemoryConfigRegistry config() {
            return config;
        }

        @Override
        public DisposableScope disposableScope() {
            return scope;
        }

        @Override
        public CubismFacade cubism() {
            return new CubismFacade() {
                @Override
                public dev.turboism.sdk.cubism.CubismRuntimeSnapshot runtime() {
                    throw unused();
                }

                @Override
                public Optional<dev.turboism.sdk.cubism.ProjectSnapshot> activeProject() {
                    return Optional.empty();
                }

                @Override
                public Optional<dev.turboism.sdk.cubism.DocumentSnapshot> activeDocument() {
                    return Optional.empty();
                }

                @Override
                public Optional<dev.turboism.sdk.cubism.ModelSnapshot> activeModel() {
                    return Optional.empty();
                }

                @Override
                public boolean isHostPresent() {
                    return false;
                }

                @Override
                public TextureAtlasLayoutService textureAtlasLayouts() {
                    return new TextureAtlasLayoutService() {
                        @Override
                        public Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot> current() {
                            return Optional.empty();
                        }

                        @Override
                        public TextureAtlasLayoutApplyResult apply(
                                final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget target,
                                final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan plan) {
                            return TextureAtlasLayoutApplyResult.failed(
                                    TextureAtlasLayoutFailureCode.PLAN_INVALID, "no snapshot");
                        }
                    };
                }

                @Override
                public TextureAtlasPolygonLayoutService textureAtlasPolygonLayouts() {
                    return new TextureAtlasPolygonLayoutService() {
                        @Override
                        public Optional<TextureAtlasPolygonLayoutSnapshot> currentPolygon() {
                            return Optional.empty();
                        }

                        @Override
                        public TextureAtlasLayoutApplyResult apply(
                                final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget target,
                                final TextureAtlasPolygonPlan plan) {
                            return TextureAtlasLayoutApplyResult.failed(
                                    TextureAtlasLayoutFailureCode.PLAN_INVALID, "no snapshot");
                        }
                    };
                }

                @Override
                public TextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms() {
                    return algorithms;
                }
            };
        }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("not used by this test");
        }
    }

    private static final class TestAlgorithmRegistry implements TextureAtlasLayoutAlgorithmRegistry {
        private final Map<String, TextureAtlasLayoutAlgorithm> algorithms = new LinkedHashMap<>();

        @Override
        public Registration register(final TextureAtlasLayoutAlgorithm algorithm) {
            algorithms.put(algorithm.id(), algorithm);
            return () -> algorithms.remove(algorithm.id(), algorithm);
        }

        @Override
        public Optional<TextureAtlasLayoutAlgorithm> find(final String id) {
            return Optional.ofNullable(algorithms.get(id));
        }

        @Override
        public List<TextureAtlasLayoutAlgorithm> algorithms() {
            return List.copyOf(algorithms.values());
        }
    }
}
