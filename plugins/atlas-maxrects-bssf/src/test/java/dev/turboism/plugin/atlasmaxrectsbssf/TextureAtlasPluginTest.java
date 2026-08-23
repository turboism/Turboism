package dev.turboism.plugin.atlasmaxrectsbssf;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.plugin.atlasmaxrectsbssf.test.DefaultPluginConfigRegistry;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutService;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAtlasPluginTest {

    @Test
    void lifecycleComposesAutomaticLayoutServiceAndRevokesCapturedAccessWhenDisabled() {
        TextureAtlasPlugin plugin = new TextureAtlasPlugin();

        assertThrows(IllegalStateException.class, plugin::enable);
        plugin.init(new ShellPluginContext());
        assertThrows(IllegalStateException.class, plugin::autoLayoutService);
        plugin.enable();

        assertTrue(plugin.isEnabled());
        final TextureAtlasAutoLayoutService captured = plugin.autoLayoutService();
        assertTrue(captured != null);
        plugin.disable();
        assertFalse(plugin.isEnabled());
        assertThrows(IllegalStateException.class, plugin::autoLayoutService);
        assertEquals(dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode.RUNTIME_CLOSED, captured.applyAutomaticLayout().failureCode().orElseThrow());

        plugin.enable();
        assertTrue(plugin.autoLayoutService() == captured);
        plugin.shutdown();
        assertFalse(plugin.isEnabled());
        assertEquals(dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode.RUNTIME_CLOSED, captured.applyAutomaticLayout().failureCode().orElseThrow());
        assertThrows(IllegalStateException.class, plugin::autoLayoutService);
    }

    @Test
    void persistsAndRecomposesTheSelectedLayoutMode() {
        final TextureAtlasPlugin plugin = new TextureAtlasPlugin();
        plugin.init(new ShellPluginContext());
        plugin.enable();

        assertEquals(TextureAtlasLayoutMode.PART_BUCKET, plugin.settings().layoutMode());
        assertTrue(plugin.updateSettings(new TextureAtlasSettings(TextureAtlasLayoutMode.COMPACT, TextureAtlasPlugin.ALGORITHM_MAXRECTS, false)));
        assertEquals(TextureAtlasLayoutMode.COMPACT, plugin.settings().layoutMode());

        plugin.disable();
        plugin.enable();
        assertEquals(TextureAtlasLayoutMode.COMPACT, plugin.settings().layoutMode());
        plugin.shutdown();
    }

    @Test
    void nativeEntryPublishesOneLifecycleBoundCallbackAndFallsBackOnFailure() {
        final RecordingLayoutService layouts = new RecordingLayoutService(
            java.util.Optional.of(snapshot())
        );
        final ShellPluginContext context = new ShellPluginContext(layouts);
        final TextureAtlasPlugin plugin = new TextureAtlasPlugin();
        plugin.init(context);

        plugin.enable();
        final Object published = System.getProperties().get(TextureAtlasPlugin.NATIVE_AUTO_LAYOUT_CALLBACK_KEY);
        assertTrue(published instanceof BooleanSupplier);
        assertTrue(((BooleanSupplier) published).getAsBoolean());
        assertEquals(1, layouts.applyCalls);
        assertTrue(context.infoMessages.contains(
            "Texture Atlas native automatic-layout result status=APPLIED"
        ));

        layouts.result = dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult.noChange();
        assertTrue(((BooleanSupplier) published).getAsBoolean());
        assertTrue(context.infoMessages.contains(
            "Texture Atlas native automatic-layout result status=NO_CHANGE"
        ));

        layouts.result = dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult.failed(
            dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode.PROVIDER_FAILED,
            "failed"
        );
        assertFalse(((BooleanSupplier) published).getAsBoolean());
        assertTrue(context.warnMessages.contains(
            "Texture Atlas native automatic-layout result failureCode=PROVIDER_FAILED"
        ));

        final BooleanSupplier replacement = () -> true;
        System.getProperties().put(TextureAtlasPlugin.NATIVE_AUTO_LAYOUT_CALLBACK_KEY, replacement);
        plugin.disable();
        assertTrue(System.getProperties().get(TextureAtlasPlugin.NATIVE_AUTO_LAYOUT_CALLBACK_KEY) == replacement);
        assertFalse(((BooleanSupplier) published).getAsBoolean());
        plugin.shutdown();
        assertTrue(System.getProperties().get(TextureAtlasPlugin.NATIVE_AUTO_LAYOUT_CALLBACK_KEY) == replacement);
        System.getProperties().remove(TextureAtlasPlugin.NATIVE_AUTO_LAYOUT_CALLBACK_KEY, replacement);
    }
    private static final class ShellPluginContext implements PluginContext {
        private final java.util.List<String> infoMessages = new java.util.ArrayList<>();
        private final java.util.List<String> warnMessages = new java.util.ArrayList<>();
        private final PluginLogger logger = new PluginLogger() {
            @Override public void debug(String message) {}
            @Override public void info(String message) { infoMessages.add(message); }
            @Override public void warn(String message) { warnMessages.add(message); }
            @Override public void error(String message) {}
            @Override public void error(String message, Throwable throwable) {}
        };
        private final TextureAtlasLayoutService layouts;
        private final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry registry =
            new TestAlgorithmRegistry();

        private ShellPluginContext() {
            this(new RecordingLayoutService(java.util.Optional.empty()));
        }

        private ShellPluginContext(final TextureAtlasLayoutService layouts) {
            this.layouts = layouts;
            registry.register(new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm(
                TextureAtlasPlugin.ALGORITHM_MAXRECTS, "MaxRects-BSSF", true,
                (items, constraints) ->
                    new dev.turboism.plugin.atlasmaxrectsbssf.layout.MaxRectsBssfTextureAtlasPlanner()
                        .plan(items, constraints, false)
            ));
            registry.register(new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm(
                TextureAtlasPlugin.ALGORITHM_NATIVE, "Native", false, null
            ));
        }

        @Override public PluginDescriptor descriptor() { throw unused(); }
        @Override public PluginLogger logger() { return logger; }
        @Override public PluginPaths paths() { throw unused(); }
        @Override public PluginConfigRegistry config() { return new DefaultPluginConfigRegistry(); }
        @Override public CubismFacade cubism() {
            return new CubismFacade() {
                @Override public dev.turboism.sdk.cubism.CubismRuntimeSnapshot runtime() { throw unused(); }
                @Override public java.util.Optional<dev.turboism.sdk.cubism.ProjectSnapshot> activeProject() { return java.util.Optional.empty(); }
                @Override public java.util.Optional<dev.turboism.sdk.cubism.DocumentSnapshot> activeDocument() { return java.util.Optional.empty(); }
                @Override public java.util.Optional<dev.turboism.sdk.cubism.ModelSnapshot> activeModel() { return java.util.Optional.empty(); }
                @Override public boolean isHostPresent() { return false; }
                @Override public dev.turboism.sdk.cubism.transaction.TransactionManager transactionManager() { throw unused(); }
                @Override public TextureAtlasLayoutService textureAtlasLayouts() {
                    return layouts;
                }
                @Override public dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry textureAtlasAlgorithms() {
                    return registry;
                }
            };
        }
        @Override public dev.turboism.sdk.i18n.PluginLocalization localization() {
            return new dev.turboism.sdk.i18n.PluginLocalization() {
                @Override public java.util.Locale locale() { return java.util.Locale.ENGLISH; }
                @Override public String text(String key) { return key; }
                @Override public String format(String key, Object... arguments) { return key; }
                @Override public boolean contains(String key) { return true; }
            };
        }
        @Override public List<PluginPermission> permissions() { return List.of(); }
        @Override public EventBus eventBus() { throw unused(); }
        @Override public ActionRegistry actions() { throw unused(); }
        @Override public MenuRegistry menus() { throw unused(); }
        @Override public UiScheduler uiScheduler() { throw unused(); }
        @Override public DiagnosticReport diagnostics() { throw unused(); }
        @Override public DisposableScope disposableScope() { throw unused(); }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("not used by a migration shell");
        }
    }

    private static dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot snapshot() {
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget target =
            new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget() { };
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints constraints =
            new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints(16, 16, 0, 0, 1, false, false);
        final java.util.List<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem> items = java.util.List.of(
            new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem("texture-a", 4, 4)
        );
        final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan plan =
            new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan(
                16,
                16,
                1,
                java.util.List.of(new dev.turboism.sdk.cubism.textureatlas.TextureAtlasPlacement(
                    "texture-a", 0, 0, 0, 4, 4, false
                ))
            );
        return new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot(
            target, "document-a", "model-a", "atlas-a", constraints, items, plan
        );
    }
    private static final class RecordingLayoutService implements TextureAtlasLayoutService {
        private final java.util.Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot> snapshot;
        private dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult result =
            dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult.applied();
        private int applyCalls;

        private RecordingLayoutService(
            final java.util.Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot> snapshot
        ) {
            this.snapshot = snapshot;
        }

        @Override
        public java.util.Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot> current() {
            return snapshot;
        }

        @Override
        public dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult apply(
            final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget target,
            final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan plan
        ) {
            applyCalls++;
            return result;
        }
    }

    private static final class TestAlgorithmRegistry
        implements dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry {
        private final java.util.Map<String, dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm>
            algorithms = new java.util.LinkedHashMap<>();

        @Override public dev.turboism.sdk.plugin.Registration register(
            dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm algorithm
        ) {
            algorithms.put(algorithm.id(), algorithm);
            return () -> algorithms.remove(algorithm.id(), algorithm);
        }

        @Override public java.util.Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm>
            find(String id) {
            return java.util.Optional.ofNullable(algorithms.get(id));
        }

        @Override public java.util.List<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm> algorithms() {
            return java.util.List.copyOf(algorithms.values());
        }
    }
}
