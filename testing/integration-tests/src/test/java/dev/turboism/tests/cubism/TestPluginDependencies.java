package dev.turboism.tests.cubism;

import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.event.EventBus;
import dev.turboism.sdk.menu.MenuRegistry;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.test.Stubs;
import dev.turboism.test.plugin.FakePluginPaths;
import dev.turboism.test.ui.FakeDirectUiScheduler;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.function.Consumer;

final class TestPluginDependencies {

    private TestPluginDependencies() {}

    static PluginDescriptor descriptor(String... permissionIds) {
        return Stubs.descriptorBuilder(CubismQueryIntegrationSupport.PLUGIN_ID)
                .name("Query Tests")
                .version("0.1.0")
                .description("Integration test descriptor")
                .entrypoints("dev.turboism.tests.QueryPlugin")
                .license("Project License")
                .website("https://turboism.dev")
                .permissions(java.util.Arrays.stream(permissionIds)
                        .map(id -> Stubs.permissionRef(id, "read"))
                        .toList())
                .build();
    }

    static PluginPaths paths() {
        return FakePluginPaths.builder(Path.of("build", "query-tests", "data"))
                .stateDir(Path.of("build", "query-tests", "state"))
                .cacheDir(Path.of("build", "query-tests", "cache"))
                .build();
    }

    static EventBus noOpEventBus() {
        return new EventBus() {
            @Override
            public <T extends TurboismEvent> Registration subscribe(final Class<T> type, final Consumer<T> listener) {
                return () -> {};
            }

            @Override
            public <T extends TurboismEvent> void publish(final T event) {}
        };
    }

    static ActionRegistry noOpActions() {
        return (id, action) -> () -> {};
    }

    static MenuRegistry noOpMenus() {
        return contribution -> () -> {};
    }

    static UiScheduler directUiScheduler() {
        return FakeDirectUiScheduler.direct();
    }

    static DiagnosticReport emptyDiagnostics() {
        return new DiagnosticReport() {
            @Override
            public Instant createdAt() {
                return CubismQueryIntegrationSupport.FIXED_CLOCK.instant();
            }

            @Override
            public List<Problem> problems() {
                return List.of();
            }
        };
    }
}
