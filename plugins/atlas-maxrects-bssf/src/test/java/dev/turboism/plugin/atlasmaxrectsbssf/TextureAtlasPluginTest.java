package dev.turboism.plugin.atlasmaxrectsbssf;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.plugin.atlasmaxrectsbssf.test.DefaultPluginConfigRegistry;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextureAtlasPluginTest {

    @Test
    void registeredPlannerPreservesExplicitCompleteAtlasSdkRequests() {
        final ShellPluginContext context = new ShellPluginContext();
        final TextureAtlasPlugin plugin = new TextureAtlasPlugin();
        plugin.init(context);
        plugin.enable();
        try {
            assertTrue(plugin.updateSettings(new TextureAtlasSettings(TextureAtlasLayoutMode.COMPACT)));
            final var planner = context.registry.find(TextureAtlasPlugin.ALGORITHM_MAXRECTS).orElseThrow().planner();
            final var plan = planner.plan(java.util.List.of(
                new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem("first", 10, 10),
                new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem("second", 10, 10)),
                new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints(16, 16, 0, 0, 2, false, false));
            assertEquals(2, plan.placements().size());
            assertEquals(2, plan.pageCount());
        } finally {
            plugin.disable();
            plugin.shutdown();
        }
    }

    @Test
    void registeredPlannerHonorsTheRuntimeParallelHintAndTwoArgCompatibility() {
        final ShellPluginContext context = new ShellPluginContext();
        final TextureAtlasPlugin plugin = new TextureAtlasPlugin();
        plugin.init(context);
        plugin.enable();
        try {
            final var items = java.util.stream.IntStream.range(0, 32).mapToObj(i ->
                new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem("image-" + i, 4, 4)).toList();
            final var constraints = dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints.currentPage(64, 64, 1, false, 1);
            final var planner = context.registry.find(TextureAtlasPlugin.ALGORITHM_MAXRECTS).orElseThrow().planner();
            final var serial = planner.plan(items, constraints);
            // The three-argument hint supplied by native dispatch is honored directly.
            final var hinted = planner.plan(items, constraints, true);
            org.junit.jupiter.api.Assertions.assertNotEquals(serial, hinted);
            assertEquals(new dev.turboism.plugin.atlasmaxrectsbssf.layout.CurrentPageTextureAtlasPlanner()
                .plan(items, constraints, true), hinted);
            // Two-argument calls read the runtime-owned selection's parallel flag.
            context.registry.select(new TextureAtlasLayoutSelection("maxrects", true));
            assertEquals(hinted, planner.plan(items, constraints));
            context.registry.select(new TextureAtlasLayoutSelection("maxrects", false));
            assertEquals(serial, planner.plan(items, constraints));
        } finally {
            plugin.shutdown();
        }
    }

    @Test
    void productionRegisteredPlannerOnlyPacksTheCurrentPageAndReturnsPartialPlacement() {
        final ShellPluginContext context = new ShellPluginContext();
        final TextureAtlasPlugin plugin = new TextureAtlasPlugin();
        plugin.init(context);
        plugin.enable();
        try {
            final var planner = context.registry.find(TextureAtlasPlugin.ALGORITHM_MAXRECTS).orElseThrow().planner();
            final var items = List.of(
                new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem("first", 10, 10),
                new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem("second", 10, 10),
                new dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem("oversized", 100, 100)
            );
            final var plan = planner.plan(items,
                dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutConstraints.currentPage(16, 16, 0, false, 1));
            assertEquals(1, plan.pageCount());
            assertEquals(1, plan.placements().size());
            assertEquals("first", plan.placements().get(0).textureId());
            assertTrue(context.warnMessages.isEmpty());
        } finally {
            plugin.shutdown();
        }
    }

    @Test
    void initRegistersTheProductionTextureAtlasSchema() {
        final TextureAtlasPlugin plugin = new TextureAtlasPlugin();
        final ShellPluginContext context = new ShellPluginContext();

        plugin.init(context);

        assertEquals(TextureAtlasSettingsBinding.CONFIG_ID, context.config.lastSchema().configId());
        assertEquals(TextureAtlasSettingsBinding.CONFIG_PATH, context.config.lastSchema().relativePath());
        assertEquals(4, context.config.lastSchema().version());
        plugin.shutdown();
    }

    @Test
    void enableRegistersTheAlgorithmAndScopeCloseUnregistersIt() throws Exception {
        final TextureAtlasPlugin plugin = new TextureAtlasPlugin();
        final ShellPluginContext context = new ShellPluginContext();

        assertThrows(IllegalStateException.class, plugin::enable);
        plugin.init(context);
        plugin.enable();

        assertTrue(plugin.isEnabled());
        assertTrue(context.registry.find(TextureAtlasPlugin.ALGORITHM_MAXRECTS).isPresent());
        // Registering must not force a runtime selection.
        assertTrue(context.registry.selection().isNative());

        plugin.disable();
        assertFalse(plugin.isEnabled());
        // Disabling the owning scope detaches the registration without an explicit close.
        context.scope.close();
        assertTrue(context.registry.find(TextureAtlasPlugin.ALGORITHM_MAXRECTS).isEmpty());
        plugin.shutdown();
    }

    @Test
    void enablePublishesNoPluginOwnedSystemProperties() {
        final ShellPluginContext context = new ShellPluginContext();
        final TextureAtlasPlugin plugin = new TextureAtlasPlugin();
        plugin.init(context);
        plugin.enable();
        try {
            assertNull(System.getProperty("dev.turboism.texture-atlas.auto-layout.callback"));
            assertNull(System.getProperty("dev.turboism.texture-atlas.dialog.algorithm"));
            assertNull(System.getProperty("dev.turboism.texture-atlas.dialog.parallel"));
        } finally {
            plugin.shutdown();
        }
    }

    @Test
    void persistsAndRecomposesTheSelectedLayoutMode() {
        final TextureAtlasPlugin plugin = new TextureAtlasPlugin();
        plugin.init(new ShellPluginContext());
        plugin.enable();

        assertEquals(TextureAtlasLayoutMode.PART_BUCKET, plugin.settings().layoutMode());
        assertTrue(plugin.updateSettings(new TextureAtlasSettings(TextureAtlasLayoutMode.COMPACT)));
        assertEquals(TextureAtlasLayoutMode.COMPACT, plugin.settings().layoutMode());

        plugin.disable();
        plugin.enable();
        assertEquals(TextureAtlasLayoutMode.COMPACT, plugin.settings().layoutMode());
        plugin.shutdown();
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
        private final TextureAtlasLayoutService layouts = new EmptyLayoutService();
        private final DisposableScope scope = new DisposableScope();
        private final TestAlgorithmRegistry registry = new TestAlgorithmRegistry(scope);
        private final DefaultPluginConfigRegistry config = new DefaultPluginConfigRegistry();

        @Override public PluginDescriptor descriptor() { throw unused(); }
        @Override public PluginLogger logger() { return logger; }
        @Override public PluginPaths paths() { throw unused(); }
        @Override public PluginConfigRegistry config() { return config; }
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
        @Override public DisposableScope disposableScope() { return scope; }

        private static UnsupportedOperationException unused() {
            return new UnsupportedOperationException("not used by this plugin");
        }
    }

    private static final class EmptyLayoutService implements TextureAtlasLayoutService {
        @Override
        public java.util.Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSnapshot> current() {
            return java.util.Optional.empty();
        }

        @Override
        public dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult apply(
            final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutTarget target,
            final dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutPlan plan
        ) {
            return dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutApplyResult.failed(
                dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutFailureCode.CAPABILITY_UNAVAILABLE,
                "no atlas"
            );
        }
    }

    /**
     * Stand-in for the production facade registry: registrations are bound to the
     * owning plugin scope the same way {@code CubismFacadeImpl} ties them, so a scope
     * close detaches them even when the plugin never closes explicitly.
     */
    private static final class TestAlgorithmRegistry
        implements dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithmRegistry {
        private final java.util.Map<String, dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm>
            algorithms = new java.util.LinkedHashMap<>();
        private final DisposableScope ownerScope;
        private TextureAtlasLayoutSelection selection = TextureAtlasLayoutSelection.nativeDefault();

        private TestAlgorithmRegistry(final DisposableScope ownerScope) {
            this.ownerScope = ownerScope;
        }

        @Override public dev.turboism.sdk.plugin.Registration register(
            dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm algorithm
        ) {
            algorithms.put(algorithm.id(), algorithm);
            final dev.turboism.sdk.plugin.Registration close =
                () -> algorithms.remove(algorithm.id(), algorithm);
            ownerScope.register(close);
            return close;
        }

        @Override public java.util.Optional<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm>
            find(String id) {
            return java.util.Optional.ofNullable(algorithms.get(id));
        }

        @Override public java.util.List<dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutAlgorithm> algorithms() {
            return java.util.List.copyOf(algorithms.values());
        }

        @Override public TextureAtlasLayoutSelection selection() {
            return selection;
        }

        @Override public void select(final TextureAtlasLayoutSelection next) {
            selection = next;
        }
    }
}
