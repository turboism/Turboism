package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.sdk.cubism.EditorExitResult;
import dev.turboism.sdk.cubism.EditorLifecycleSnapshot;
import dev.turboism.sdk.cubism.hook.EditorLifecycleHooks;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EditorLifecycleCoordinatorTest {

    @Test
    void startupAndAcceptedExitUseBeforeOnAfterOrder() {
        final List<String> events = new CopyOnWriteArrayList<>();
        final EditorLifecycleCoordinator coordinator = new EditorLifecycleCoordinator();
        coordinator.register(plugin(new EditorLifecycleHooks() {
            @Override public void beforeEditorStartup(final EditorLifecycleSnapshot editor) {
                events.add("before-start:" + editor.hostVersion());
            }
            @Override public void onEditorStarted(final EditorLifecycleSnapshot editor) {
                events.add("on-start");
            }
            @Override public void afterEditorStartup(final EditorLifecycleSnapshot editor) {
                events.add("after-start");
            }
            @Override public void beforeEditorExit(final EditorLifecycleSnapshot editor) {
                events.add("before-exit");
            }
            @Override public void onEditorExiting(final EditorLifecycleSnapshot editor) {
                events.add("on-exit");
            }
            @Override public void afterEditorExit(final EditorExitResult result) {
                events.add("after-exit:" + result.accepted());
            }
        }));

        coordinator.publishStartup("5.3.02");
        coordinator.awaitIdle();
        final EditorLifecycleCoordinator.ExitInvocation exit = coordinator.beginExit("5.3.02");
        assertEquals(List.of(
            "before-start:5.3.02",
            "on-start",
            "after-start",
            "before-exit"
        ), events);
        coordinator.completeExit(exit, true, null);
        coordinator.awaitIdle();

        assertEquals(List.of(
            "before-start:5.3.02",
            "on-start",
            "after-start",
            "before-exit",
            "on-exit",
            "after-exit:true"
        ), events);
        coordinator.close();
    }

    @Test
    void rejectedOrFailedExitDoesNotPublishOnExiting() {
        final List<String> events = new CopyOnWriteArrayList<>();
        final EditorLifecycleCoordinator coordinator = new EditorLifecycleCoordinator();
        coordinator.register(plugin(new EditorLifecycleHooks() {
            @Override public void beforeEditorExit(final EditorLifecycleSnapshot editor) {
                events.add("before");
            }
            @Override public void onEditorExiting(final EditorLifecycleSnapshot editor) {
                events.add("unexpected-on");
            }
            @Override public void afterEditorExit(final EditorExitResult result) {
                events.add("after:" + result.accepted() + ":" + result.failureType().orElse("none"));
            }
        }));

        final var rejected = coordinator.beginExit("5.3.02");
        coordinator.completeExit(rejected, false, null);
        final var failed = coordinator.beginExit("5.3.02");
        coordinator.completeExit(failed, false, new IllegalStateException("native"));
        coordinator.awaitIdle();

        assertEquals(List.of(
            "before",
            "after:false:none",
            "before",
            "after:false:java.lang.IllegalStateException"
        ), events);
        coordinator.close();
    }

    @Test
    void pluginRegisteredAfterStartupReceivesCurrentStartupSnapshot() {
        final List<String> events = new CopyOnWriteArrayList<>();
        final EditorLifecycleCoordinator coordinator = new EditorLifecycleCoordinator();
        coordinator.publishStartup("5.2.03");
        coordinator.register(plugin(new EditorLifecycleHooks() {
            @Override public void beforeEditorStartup(final EditorLifecycleSnapshot editor) {
                events.add("before:" + editor.hostVersion());
            }
            @Override public void onEditorStarted(final EditorLifecycleSnapshot editor) {
                events.add("on");
            }
            @Override public void afterEditorStartup(final EditorLifecycleSnapshot editor) {
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
            @Override public void beforeEditorStartup(final EditorLifecycleSnapshot editor) {
                events.add("before:" + editor.hostVersion());
            }
            @Override public void onEditorStarted(final EditorLifecycleSnapshot editor) {
                events.add("on");
            }
            @Override public void afterEditorStartup(final EditorLifecycleSnapshot editor) {
                events.add("after");
            }
        }));
        coordinator.awaitIdle();

        assertEquals(List.of("before:5.3.03", "on", "after"), events);
        coordinator.close();
    }

    private static EditorLifecycleCoordinator.PluginHooks plugin(
        final EditorLifecycleHooks hooks
    ) {
        return new EditorLifecycleCoordinator.PluginHooks(
            descriptor(),
            List.of(hooks),
            logger(),
            true
        );
    }

    private static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override public String id() { return "plugin-editor"; }
            @Override public String name() { return "plugin-editor"; }
            @Override public String version() { return "1.0.0"; }
            @Override public String description() { return "test"; }
            @Override public List<String> entrypoints() { return List.of(); }
            @Override public String turboismApi() { return "[0.1.0,0.2.0)"; }
            @Override public List<Author> authors() { return List.of(); }
            @Override public String license() { return "Test"; }
            @Override public Optional<String> website() { return Optional.empty(); }
            @Override public List<String> resources() { return List.of(); }
            @Override public I18n i18n() { return new I18n() {
                @Override public String baseName() { return "messages"; }
                @Override public List<String> locales() { return List.of(); }
            }; }
            @Override public List<DependencyRef> dependencies() { return List.of(); }
            @Override public List<PermissionRef> permissions() { return List.of(); }
            @Override public List<String> capabilities() { return List.of(); }
            @Override public Environment environment() { return new Environment() {
                @Override public boolean requiresCubism() { return false; }
                @Override public String ui() { return "none"; }
            }; }
        };
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override public void debug(final String message) { }
            @Override public void info(final String message) { }
            @Override public void warn(final String message) { }
            @Override public void error(final String message) { }
            @Override public void error(final String message, final Throwable throwable) { }
        };
    }
}
