package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.core.event.PluginEventOwnerKey;
import dev.turboism.sdk.cubism.EditorLifecycleSnapshot;
import dev.turboism.sdk.cubism.hook.EditorLifecycleHooks;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProjectLifecycleHookRegistryTest {

    @Test
    void exactOwnerUnregisterDetachesItsEditorCompatibilityTokenOnly() throws Exception {
        final List<String> events = new CopyOnWriteArrayList<>();
        final EditorLifecycleCoordinator editor = new EditorLifecycleCoordinator();
        final ProjectLifecycleHookRegistry registry = new ProjectLifecycleHookRegistry(
            new ProjectFileLifecycleCoordinator(),
            editor
        );
        final DisposableScope firstScope = new DisposableScope();
        final DisposableScope secondScope = new DisposableScope();
        final dev.turboism.core.runtime.RuntimeScheduler scheduler = testScheduler();
        final dev.turboism.core.event.RuntimeEventBroker broker =
            new dev.turboism.core.event.RuntimeEventBroker(scheduler);
        final PluginEventOwnerKey first = broker.admit("plugin-editor").key();
        final PluginEventOwnerKey second = broker.admit("plugin-editor").key();
        registry.register(
            descriptor(),
            List.of(new EditorPlugin("first", events)),
            logger(),
            firstScope,
            broker,
            first
        );
        registry.register(
            descriptor(),
            List.of(new EditorPlugin("second", events)),
            logger(),
            secondScope,
            broker,
            second
        );

        registry.unregister(first);
        editor.publishStartup("5.3.02");
        editor.awaitIdle();

        assertEquals(List.of("second"), events);
        firstScope.close();
        secondScope.close();
        editor.close();
        scheduler.shutdown();
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
            @Override public List<PermissionRef> permissions() {
                return List.of(new PermissionRef() {
                    @Override public String id() {
                        return PermissionIds.TURBOISM_CUBISM_MODEL_OBSERVE;
                    }
                    @Override public String scope() { return "runtime"; }
                    @Override public Optional<String> reason() { return Optional.empty(); }
                });
            }
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

    private static dev.turboism.core.runtime.RuntimeScheduler testScheduler() {
        return new dev.turboism.core.runtime.RuntimeScheduler(
            new dev.turboism.core.runtime.DefaultWorkBudgetPolicy(),
            new dev.turboism.core.runtime.work.PluginWorkExecutorRegistry(
                1,
                8,
                ignored -> { },
                java.time.Clock.systemUTC()
            ),
            dev.turboism.core.runtime.sidecar.SidecarDispatcher.noop(),
            ignored -> { }
        );
    }
}
