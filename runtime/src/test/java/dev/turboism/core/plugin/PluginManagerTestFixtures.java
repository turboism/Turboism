package dev.turboism.core.plugin;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

final class PluginManagerTestFixtures {

    static final String PLUGIN_ID = "dev.turboism.plugin.test";

    private PluginManagerTestFixtures() {
    }

    static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override
            public String id() {
                return PLUGIN_ID;
            }

            @Override
            public String name() {
                return "Test Plugin";
            }

            @Override
            public String version() {
                return "0.1.0";
            }

            @Override
            public String description() {
                return "Test plugin";
            }

            @Override
            public Map<String, String> entrypoints() {
                return Map.of("plugin", "dev.turboism.plugin.TestPlugin");
            }

            @Override
            public String turboismApi() {
                return "[0.1.0,0.2.0)";
            }

            @Override
            public List<Author> authors() {
                return List.of();
            }

            @Override
            public String license() {
                return "Project License";
            }

            @Override
            public Optional<String> homepage() {
                return Optional.empty();
            }

            @Override
            public List<DependencyRef> dependencies() {
                return List.of();
            }

            @Override
            public List<PermissionRef> permissions() {
                return List.of();
            }

            @Override
            public List<String> capabilities() {
                return List.of();
            }

            @Override
            public Environment environment() {
                return new Environment() {
                    @Override
                    public boolean requiresCubism() {
                        return false;
                    }

                    @Override
                    public String ui() {
                        return "none";
                    }
                };
            }
        };
    }

    static PluginContext context(DisposableScope scope) {
        return (PluginContext) Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[] { PluginContext.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "descriptor" -> descriptor();
                case "disposableScope" -> scope;
                case "permissions" -> List.of();
                case "toString" -> "PluginManagerTestContext";
                default -> throw new UnsupportedOperationException(method.getName() + " not used");
            }
        );
    }

    static final class ImmediateSidecarDispatcher implements SidecarDispatcher {

        @Override
        public CompletionStage<SidecarResult> dispatch(PluginTask task, Runnable callback) {
            callback.run();
            return CompletableFuture.completedFuture(SidecarResult.success(""));
        }
    }
}
