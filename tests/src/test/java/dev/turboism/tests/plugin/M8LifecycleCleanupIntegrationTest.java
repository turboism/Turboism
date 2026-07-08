package dev.turboism.tests.plugin;

import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.plugin.PluginManager;
import dev.turboism.core.plugin.PluginRuntime;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.test.plugin.PermissionProbePlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M8LifecycleCleanupIntegrationTest {

    @Test
    void disableClosesAllRegistrations(@TempDir Path dataDir) throws Exception {
        // Given
        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(dataDir, PermissionChecker.allowAll())) {
            PermissionProbePlugin plugin = enabledPlugin(harness);

            // When
            plugin.disable();

            // Then
            assertEquals(1, plugin.actionRegistrationCount());
            assertFalse(harness.toolbarTracker().isVisible(M8PluginTestSupport.PLUGIN_ID, "probe.toolbar"));
            assertFalse(harness.menuTracker().isVisible("probe.action"));
            assertConfigScopeClosed(harness);
        }
    }

    @Test
    void shutdownAfterPartialLoadClosesScope(@TempDir Path dataDir) throws Exception {
        // Given
        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(dataDir, PermissionChecker.allowAll())) {
            PermissionProbePlugin plugin = new PermissionProbePlugin();
            plugin.init(harness.context());
            plugin.enable();

            // When
            harness.context().disposableScope().close();
            plugin.shutdown();

            // Then
            assertFalse(harness.toolbarTracker().isVisible(M8PluginTestSupport.PLUGIN_ID, "probe.toolbar"));
            assertFalse(harness.menuTracker().isVisible("probe.action"));
            assertConfigScopeClosed(harness);
        }
    }

    @Test
    void enableFailureTriggersCleanup(@TempDir Path dataDir) throws Exception {
        // Given
        try (M8PluginTestSupport.Harness harness = M8PluginTestSupport.harness(dataDir, PermissionChecker.allowAll())) {
            PermissionProbePlugin plugin = new PermissionProbePlugin(true);
            plugin.init(harness.context());
            PluginRuntime runtime = runtime(plugin, harness.context());
            PluginManager manager = new PluginManager(harness.scheduler());
            manager.registerDescriptor(runtime);

            // When
            manager.enable(M8PluginTestSupport.PLUGIN_ID);
            awaitState(runtime, PluginLifecycleState.ENABLE_FAILED);

            // Then
            assertFalse(harness.toolbarTracker().isVisible(M8PluginTestSupport.PLUGIN_ID, "probe.toolbar"));
            assertFalse(harness.menuTracker().isVisible("probe.action"));
            assertConfigScopeClosed(harness);
        }
    }

    private static PermissionProbePlugin enabledPlugin(M8PluginTestSupport.Harness harness) throws Exception {
        PermissionProbePlugin plugin = new PermissionProbePlugin();
        plugin.init(harness.context());
        plugin.enable();
        assertTrue(harness.toolbarTracker().isVisible(M8PluginTestSupport.PLUGIN_ID, "probe.toolbar"));
        assertTrue(harness.menuTracker().isVisible("probe.action"));
        return plugin;
    }

    private static PluginRuntime runtime(PermissionProbePlugin plugin, M8PluginTestSupport.TestPluginContext context) {
        PluginRuntime runtime = new PluginRuntime(M8PluginTestSupport.PLUGIN_ID, M8PluginTestSupport.descriptor());
        runtime.setInstance(plugin);
        runtime.setContext(context);
        runtime.transitionTo(PluginLifecycleState.LOADED);
        return runtime;
    }

    private static void assertConfigScopeClosed(M8PluginTestSupport.Harness harness) {
        assertThrowsIllegalState(() -> harness.config().writeString("probe/config.json", "name", "value"));
    }

    private static void assertThrowsIllegalState(ThrowingRunnable runnable) {
        try {
            runnable.run();
        } catch (IllegalStateException exception) {
            return;
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        throw new AssertionError("Expected IllegalStateException");
    }

    private static void awaitState(PluginRuntime runtime, PluginLifecycleState expected) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadlineNanos && runtime.state() != expected) {
            Thread.sleep(10);
        }
        assertEquals(expected, runtime.state());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
