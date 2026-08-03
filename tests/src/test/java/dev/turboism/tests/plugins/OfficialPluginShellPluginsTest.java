package dev.turboism.tests.plugins;

import dev.turboism.plugin.boundingbox.BoundingBoxPlugin;
import dev.turboism.plugin.contextmenu.ContextMenuPlugin;
import dev.turboism.plugin.projectpanel.ProjectPanelPlugin;
import dev.turboism.plugin.psdimport.PsdImportPlugin;
import dev.turboism.plugin.textureatlas.TextureAtlasPlugin;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigReadResult;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.ConfigValue;
import dev.turboism.sdk.config.ConfigValueSource;
import dev.turboism.sdk.config.ConfigWriteResult;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.context.ContextMenuRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class OfficialPluginShellPluginsTest {

    @ParameterizedTest
    @MethodSource("shellPlugins")
    void shellHasNoRegistrationsOrHostAccess(final Supplier<TurboismPlugin> shellFactory) {
        TurboismPlugin plugin = shellFactory.get();
        ShellPluginContext context = new ShellPluginContext();

        assertDoesNotThrow(() -> {
            plugin.init(context);
            plugin.enable();
            plugin.disable();
            plugin.enable();
            plugin.disable();
            plugin.shutdown();
        });
        context.assertNoOperationalAccess();
    }

    private static Stream<Supplier<TurboismPlugin>> shellPlugins() {
        return Stream.of(
            BoundingBoxPlugin::new,
            ProjectPanelPlugin::new,
            PsdImportPlugin::new,
            TextureAtlasPlugin::new
        );
    }

    private static final class ShellPluginContext implements PluginContext {
        private boolean operationalAccessed;
        private final PluginConfigRegistry config = new DefaultConfigRegistry();
        private final PluginLogger logger = new PluginLogger() {
            @Override public void debug(String message) {}
            @Override public void info(String message) {}
            @Override public void warn(String message) {}
            @Override public void error(String message) {}
            @Override public void error(String message, Throwable throwable) {}
        };

        @Override public PluginDescriptor descriptor() { return accessed(); }
        @Override public PluginLogger logger() { return logger; }
        @Override public PluginPaths paths() { return accessed(); }
        @Override public PluginConfigRegistry config() { return config; }
        @Override public CubismFacade cubism() { return accessed(); }
        @Override public List<PluginPermission> permissions() { return accessed(); }
        @Override public EventBus eventBus() { return accessed(); }
        @Override public ActionRegistry actions() { return accessed(); }
        @Override public MenuRegistry menus() { return accessed(); }
        @Override public UiScheduler uiScheduler() { return accessed(); }
        @Override public ContextMenuRegistry contextMenu() { return contribution -> () -> { }; }
        @Override public PluginLocalization localization() {
            return new PluginLocalization() {
                @Override public java.util.Locale locale() { return java.util.Locale.ENGLISH; }
                @Override public String text(String key) { return key; }
                @Override public String format(String key, Object... arguments) { return key; }
                @Override public boolean contains(String key) { return true; }
            };
        }
        @Override public DiagnosticReport diagnostics() { return accessed(); }
        @Override public DisposableScope disposableScope() { return accessed(); }

        void assertNoOperationalAccess() {
            if (operationalAccessed) {
                throw new AssertionError("plugin shell must not access an operational PluginContext service");
            }
        }

        private <T> T accessed() {
            operationalAccessed = true;
            throw new AssertionError("plugin shell must not access an operational PluginContext service");
        }
    }

    private static final class DefaultConfigRegistry implements PluginConfigRegistry {
        @Override public CompletionStage<Void> registerSchema(ConfigSchema schema, List<dev.turboism.sdk.config.ConfigMigration> migrations) { return CompletableFuture.completedFuture(null); }
        @Override public <T> CompletionStage<ConfigReadResult<T>> read(ConfigKey<T> key) { return CompletableFuture.completedFuture(new ConfigReadResult<>(new ConfigValue<>(key.defaultValue(), ConfigValueSource.DEFAULT_MISSING, 0), Optional.empty())); }
        @Override public <T> CompletionStage<ConfigWriteResult> write(ConfigKey<T> key, T value, long expectedRevision) { return CompletableFuture.completedFuture(new ConfigWriteResult(true, expectedRevision + 1, Optional.empty())); }
        @Override public Registration readScope(String relativePath) { return () -> { }; }
        @Override public Registration writeScope(String relativePath) { return () -> { }; }
        @Override public Optional<String> readString(String relativePath, String key) { return Optional.empty(); }
        @Override public void writeString(String relativePath, String key, String value) throws PluginConfigException { }
    }
}
