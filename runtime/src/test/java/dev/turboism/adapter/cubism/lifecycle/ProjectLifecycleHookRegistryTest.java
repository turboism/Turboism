package dev.turboism.adapter.cubism.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.event.PluginEventOwnerKey;
import dev.turboism.sdk.cubism.EditorLifecycleSnapshot;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.cubism.hook.EditorLifecycleHooks;
import dev.turboism.sdk.cubism.hook.ModelFileHooks;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.test.Stubs;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class ProjectLifecycleHookRegistryTest {

    @Test
    void exactOwnerUnregisterDetachesItsEditorCompatibilityTokenOnly() throws Exception {
        final List<String> events = new CopyOnWriteArrayList<>();
        final EditorLifecycleCoordinator editor = new EditorLifecycleCoordinator();
        final ProjectLifecycleHookRegistry registry =
                new ProjectLifecycleHookRegistry(new ProjectFileLifecycleCoordinator(), editor);
        final DisposableScope firstScope = new DisposableScope();
        final DisposableScope secondScope = new DisposableScope();
        final dev.turboism.core.runtime.RuntimeScheduler scheduler = testScheduler();
        final dev.turboism.core.event.RuntimeEventBroker broker =
                new dev.turboism.core.event.RuntimeEventBroker(scheduler);
        final PluginEventOwnerKey first = broker.admit("plugin-editor").key();
        final PluginEventOwnerKey second = broker.admit("plugin-editor").key();
        registry.register(
                descriptor(), List.of(new EditorPlugin("first", events)), logger(), firstScope, broker, first);
        registry.register(
                descriptor(), List.of(new EditorPlugin("second", events)), logger(), secondScope, broker, second);

        registry.unregister(first);
        editor.publishStartup("5.3.02");
        editor.awaitIdle();

        assertEquals(List.of("second"), events);
        firstScope.close();
        secondScope.close();
        editor.close();
        scheduler.shutdown();
    }

    @Test
    void registerWithoutObserveWarnsAboutSuppressedLifecycleOverrides() throws Exception {
        final ProjectLifecycleHookRegistry registry = new ProjectLifecycleHookRegistry(
                new ProjectFileLifecycleCoordinator(), new EditorLifecycleCoordinator());
        final RecordingLogger logger = new RecordingLogger();
        final DisposableScope scope = new DisposableScope();

        registry.register(descriptor(List.of()), List.of(new ModelPlugin(), new PassiveModelHooks()), logger, scope);

        assertEquals(1, logger.warnings.size());
        final String warning = logger.warnings.get(0);
        assertTrue(warning.contains("plugin-editor"), warning);
        assertTrue(warning.contains(PermissionIds.TURBOISM_CUBISM_MODEL_OBSERVE), warning);
        assertTrue(warning.contains("onModelSaved"), warning);
        scope.close();
    }

    @Test
    void registerWithoutObserveStaysQuietWhenNoLifecycleCallbackIsOverridden() throws Exception {
        final ProjectLifecycleHookRegistry registry = new ProjectLifecycleHookRegistry(
                new ProjectFileLifecycleCoordinator(), new EditorLifecycleCoordinator());
        final RecordingLogger logger = new RecordingLogger();
        final DisposableScope scope = new DisposableScope();

        registry.register(descriptor(List.of()), List.of(new PassiveModelHooks()), logger, scope);

        assertTrue(logger.warnings.isEmpty(), () -> String.join("\n", logger.warnings));
        scope.close();
    }

    @Test
    void registerWithObserveDoesNotWarnAboutSuppressedOverrides() throws Exception {
        final ProjectLifecycleHookRegistry registry = new ProjectLifecycleHookRegistry(
                new ProjectFileLifecycleCoordinator(), new EditorLifecycleCoordinator());
        final RecordingLogger logger = new RecordingLogger();
        final DisposableScope scope = new DisposableScope();
        final dev.turboism.core.runtime.RuntimeScheduler scheduler = testScheduler();
        final dev.turboism.core.event.RuntimeEventBroker broker =
                new dev.turboism.core.event.RuntimeEventBroker(scheduler);
        final PluginEventOwnerKey owner = broker.admit("plugin-editor").key();

        registry.register(descriptor(), List.of(new ModelPlugin()), logger, scope, broker, owner);

        assertTrue(logger.warnings.isEmpty(), () -> String.join("\n", logger.warnings));
        scope.close();
        scheduler.shutdown();
    }

    private static final class ModelPlugin implements TurboismPlugin, ModelFileHooks {
        @Override
        public void onModelSaved(final ProjectContentSnapshot model) {}
    }

    /** Implements the hook interface but overrides nothing, like a defaulted CubismPlugin. */
    private static final class PassiveModelHooks implements TurboismPlugin, ModelFileHooks {}

    private static final class RecordingLogger implements PluginLogger {
        private final List<String> warnings = new CopyOnWriteArrayList<>();

        @Override
        public void debug(final String message) {}

        @Override
        public void info(final String message) {}

        @Override
        public void warn(final String message) {
            warnings.add(message);
        }

        @Override
        public void error(final String message) {}

        @Override
        public void error(final String message, final Throwable throwable) {}
    }

    private static final class EditorPlugin implements TurboismPlugin, EditorLifecycleHooks {
        private final String id;
        private final List<String> events;

        private EditorPlugin(final String id, final List<String> events) {
            this.id = id;
            this.events = events;
        }

        @Override
        public void onEditorStarted(final EditorLifecycleSnapshot editor) {
            events.add(id);
        }
    }

    private static PluginDescriptor descriptor() {
        return descriptor(List.of(PermissionIds.TURBOISM_CUBISM_MODEL_OBSERVE));
    }

    private static PluginDescriptor descriptor(final List<String> permissionIds) {
        return Stubs.descriptorBuilder("plugin-editor")
                .license("Test")
                .permissions(permissionIds.stream().map(Stubs::permissionRef).toList())
                .build();
    }

    private static PluginLogger logger() {
        return Stubs.noopLogger();
    }

    private static dev.turboism.core.runtime.RuntimeScheduler testScheduler() {
        return new dev.turboism.core.runtime.RuntimeScheduler(
                new dev.turboism.core.runtime.DefaultWorkBudgetPolicy(),
                new dev.turboism.core.runtime.work.PluginWorkExecutorRegistry(
                        1, 8, ignored -> {}, java.time.Clock.systemUTC()),
                ignored -> {});
    }
}
