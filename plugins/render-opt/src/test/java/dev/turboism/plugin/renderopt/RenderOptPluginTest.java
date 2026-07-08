package dev.turboism.plugin.renderopt;

import dev.turboism.sdk.action.ActionRegistry;
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
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RenderOptPluginTest {

    @Test
    void enableRegistersLifecycleProviderInDisposableScope() throws Exception {
        // Given
        DisposableScope scope = new DisposableScope();
        TestPluginLogger logger = new TestPluginLogger();
        RenderOptPlugin plugin = new RenderOptPlugin();
        plugin.init(new TestPluginContext(scope, logger));

        // When
        plugin.enable();
        scope.close();

        // Then
        assertEquals(
            List.of(
                "INFO: RenderOptPlugin initialized",
                "INFO: RenderOptPlugin enabled: render optimization lifecycle provider registered",
                "INFO: RenderOptPlugin render optimization lifecycle provider disposed"
            ),
            logger.messages()
        );
    }

    private record TestPluginContext(DisposableScope disposableScope, PluginLogger logger) implements PluginContext {

        @Override
        public PluginDescriptor descriptor() {
            throw new UnsupportedOperationException("descriptor is not required by this test");
        }

        @Override
        public PluginPaths paths() {
            throw new UnsupportedOperationException("paths are not required by this test");
        }

        @Override
        public CubismFacade cubism() {
            throw new UnsupportedOperationException("cubism is not required by this test");
        }

        @Override
        public List<PluginPermission> permissions() {
            return List.of();
        }

        @Override
        public EventBus eventBus() {
            throw new UnsupportedOperationException("event bus is not required by this test");
        }

        @Override
        public ActionRegistry actions() {
            throw new UnsupportedOperationException("actions are not required by this test");
        }

        @Override
        public MenuRegistry menus() {
            throw new UnsupportedOperationException("menus are not required by this test");
        }

        @Override
        public UiScheduler uiScheduler() {
            throw new UnsupportedOperationException("ui scheduler is not required by this test");
        }

        @Override
        public DiagnosticReport diagnostics() {
            throw new UnsupportedOperationException("diagnostics are not required by this test");
        }
    }

    private static final class TestPluginLogger implements PluginLogger {

        private final List<String> messages = new java.util.ArrayList<>();

        @Override
        public void debug(String message) {
            messages.add("DEBUG: " + message);
        }

        @Override
        public void info(String message) {
            messages.add("INFO: " + message);
        }

        @Override
        public void warn(String message) {
            messages.add("WARN: " + message);
        }

        @Override
        public void error(String message) {
            messages.add("ERROR: " + message);
        }

        @Override
        public void error(String message, Throwable throwable) {
            messages.add("ERROR: " + message + ": " + throwable.getMessage());
        }

        List<String> messages() {
            return List.copyOf(messages);
        }
    }

}
