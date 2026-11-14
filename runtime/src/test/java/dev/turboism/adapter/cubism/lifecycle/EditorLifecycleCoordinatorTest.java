package dev.turboism.adapter.cubism.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.turboism.sdk.cubism.EditorExitResult;
import dev.turboism.sdk.cubism.EditorLifecycleSnapshot;
import dev.turboism.sdk.cubism.hook.EditorLifecycleHooks;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.test.Stubs;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class EditorLifecycleCoordinatorTest {

    @Test
    void startupAndAcceptedExitUseBeforeOnAfterOrder() {
        final List<String> events = new CopyOnWriteArrayList<>();
        final EditorLifecycleCoordinator coordinator = new EditorLifecycleCoordinator();
        coordinator.register(plugin(new EditorLifecycleHooks() {
            @Override
            public void beforeEditorStartup(final EditorLifecycleSnapshot editor) {
                events.add("before-start:" + editor.hostVersion());
            }

            @Override
            public void onEditorStarted(final EditorLifecycleSnapshot editor) {
                events.add("on-start");
            }

            @Override
            public void afterEditorStartup(final EditorLifecycleSnapshot editor) {
                events.add("after-start");
            }

            @Override
            public void beforeEditorExit(final EditorLifecycleSnapshot editor) {
                events.add("before-exit");
            }

            @Override
            public void onEditorExiting(final EditorLifecycleSnapshot editor) {
                events.add("on-exit");
            }

            @Override
            public void afterEditorExit(final EditorExitResult result) {
                events.add("after-exit:" + result.accepted());
            }
        }));

        coordinator.publishStartup("5.3.02");
        coordinator.awaitIdle();
        final EditorLifecycleCoordinator.ExitInvocation exit = coordinator.beginExit("5.3.02");
        assertEquals(List.of("before-start:5.3.02", "on-start", "after-start", "before-exit"), events);
        coordinator.completeExit(exit, true, null);
        coordinator.awaitIdle();

        assertEquals(
                List.of("before-start:5.3.02", "on-start", "after-start", "before-exit", "on-exit", "after-exit:true"),
                events);
        coordinator.close();
    }

    @Test
    void rejectedOrFailedExitDoesNotPublishOnExiting() {
        final List<String> events = new CopyOnWriteArrayList<>();
        final EditorLifecycleCoordinator coordinator = new EditorLifecycleCoordinator();
        coordinator.register(plugin(new EditorLifecycleHooks() {
            @Override
            public void beforeEditorExit(final EditorLifecycleSnapshot editor) {
                events.add("before");
            }

            @Override
            public void onEditorExiting(final EditorLifecycleSnapshot editor) {
                events.add("unexpected-on");
            }

            @Override
            public void afterEditorExit(final EditorExitResult result) {
                events.add("after:" + result.accepted() + ":"
                        + result.failureType().orElse("none"));
            }
        }));

        final var rejected = coordinator.beginExit("5.3.02");
        coordinator.completeExit(rejected, false, null);
        final var failed = coordinator.beginExit("5.3.02");
        coordinator.completeExit(failed, false, new IllegalStateException("native"));
        coordinator.awaitIdle();

        assertEquals(
                List.of("before", "after:false:none", "before", "after:false:java.lang.IllegalStateException"), events);
        coordinator.close();
    }

    @Test
    void pluginRegisteredAfterStartupReceivesCurrentStartupSnapshot() {
        final List<String> events = new CopyOnWriteArrayList<>();
        final EditorLifecycleCoordinator coordinator = new EditorLifecycleCoordinator();
        coordinator.publishStartup("5.2.03");
        coordinator.register(plugin(new EditorLifecycleHooks() {
            @Override
            public void beforeEditorStartup(final EditorLifecycleSnapshot editor) {
                events.add("before:" + editor.hostVersion());
            }

            @Override
            public void onEditorStarted(final EditorLifecycleSnapshot editor) {
                events.add("on");
            }

            @Override
            public void afterEditorStartup(final EditorLifecycleSnapshot editor) {
                events.add("after");
            }
        }));
        coordinator.awaitIdle();

        assertEquals(List.of("before:5.2.03", "on", "after"), events);
        coordinator.close();
    }

    @Test
    void scopeBoundPluginRegisteredAfterStartupReceivesCurrent5303Snapshot() {
        final List<String> events = new CopyOnWriteArrayList<>();
        final EditorLifecycleCoordinator coordinator = new EditorLifecycleCoordinator();
        coordinator.publishStartup("5.3.03");
        coordinator.register(new Object(), plugin(new EditorLifecycleHooks() {
            @Override
            public void beforeEditorStartup(final EditorLifecycleSnapshot editor) {
                events.add("before:" + editor.hostVersion());
            }

            @Override
            public void onEditorStarted(final EditorLifecycleSnapshot editor) {
                events.add("on");
            }

            @Override
            public void afterEditorStartup(final EditorLifecycleSnapshot editor) {
                events.add("after");
            }
        }));
        coordinator.awaitIdle();

        assertEquals(List.of("before:5.3.03", "on", "after"), events);
        coordinator.close();
    }

    private static EditorLifecycleCoordinator.PluginHooks plugin(final EditorLifecycleHooks hooks) {
        return new EditorLifecycleCoordinator.PluginHooks(descriptor(), List.of(hooks), logger(), true);
    }

    private static PluginDescriptor descriptor() {
        return Stubs.descriptorBuilder("plugin-editor").license("Test").build();
    }

    private static PluginLogger logger() {
        return Stubs.noopLogger();
    }
}
