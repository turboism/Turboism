package dev.turboism.core.plugin;

import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;
import dev.turboism.sdk.plugin.Registration;

import java.lang.reflect.Proxy;
import java.util.List;
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
            public List<String> entrypoints() {
                return List.of("dev.turboism.plugin.TestPlugin");
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
            public Optional<String> website() {
                return Optional.of("https://turboism.dev");
            }

            @Override
            public List<String> resources() {
                return List.of();
            }

            @Override
            public I18n i18n() {
                return new I18n() {
                    @Override public String baseName() {
                        return "META-INF/turboism/i18n/messages";
                    }

                    @Override public List<String> locales() {
                        return List.of();
                    }
                };
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
                case "logger" -> new NoopPluginLogger();
                case "disposableScope" -> scope;
                case "permissions" -> List.of();
                case "mainToolbar" -> noopMainToolbarRegistry();
                case "paletteToolbar" -> noopPaletteToolbarRegistry();
                case "config" -> noopConfigRegistry();
                case "toString" -> "PluginManagerTestContext";
                default -> throw new UnsupportedOperationException(method.getName() + " not used");
            }
        );
    }

    private static MainToolbarRegistry noopMainToolbarRegistry() {
        return (MainToolbarRegistry) Proxy.newProxyInstance(
            MainToolbarRegistry.class.getClassLoader(),
            new Class<?>[] { MainToolbarRegistry.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "contribute" -> NOOP_REGISTRATION;
                case "toString" -> "NoopMainToolbarRegistry";
                default -> throw new UnsupportedOperationException(method.getName() + " not used");
            }
        );
    }

    private static PaletteToolbarRegistry noopPaletteToolbarRegistry() {
        return (PaletteToolbarRegistry) Proxy.newProxyInstance(
            PaletteToolbarRegistry.class.getClassLoader(),
            new Class<?>[] { PaletteToolbarRegistry.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "contribute" -> NOOP_REGISTRATION;
                case "toString" -> "NoopPaletteToolbarRegistry";
                default -> throw new UnsupportedOperationException(method.getName() + " not used");
            }
        );
    }

    private static dev.turboism.sdk.config.PluginConfigRegistry noopConfigRegistry() {
        return (dev.turboism.sdk.config.PluginConfigRegistry) Proxy.newProxyInstance(
            dev.turboism.sdk.config.PluginConfigRegistry.class.getClassLoader(),
            new Class<?>[] { dev.turboism.sdk.config.PluginConfigRegistry.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "readScope", "writeScope" -> NOOP_REGISTRATION;
                case "readString" -> Optional.empty();
                case "writeString" -> null;
                case "toString" -> "NoopPluginConfigRegistry";
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

    private static final class NoopPluginLogger implements PluginLogger {

        @Override
        public void debug(String message) {
        }

        @Override
        public void info(String message) {
        }

        @Override
        public void warn(String message) {
        }

        @Override
        public void error(String message) {
        }

        @Override
        public void error(String message, Throwable throwable) {
        }
    }

    private static final Registration NOOP_REGISTRATION = () -> {
    };
}
