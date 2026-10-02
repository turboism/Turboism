package dev.turboism.core.plugin.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.core.diagnostics.PluginWorkBudgetEvent;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.MeshToolRegistry;
import dev.turboism.sdk.cubism.mesh.MeshToolbarSlider;
import dev.turboism.sdk.diagnostics.DiagnosticReport;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CorePluginContextMeshToolsTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDir;

    @Test
    void meshToolsFailClosedUntilTheRuntimeInstallsTheExactService() throws Exception {
        RuntimeScheduler scheduler = scheduler();
        CorePluginContext context = new CorePluginContext(dependencies(scheduler));
        try {
            dev.turboism.sdk.plugin.PluginServiceUnavailableException failure = assertThrows(
                    dev.turboism.sdk.plugin.PluginServiceUnavailableException.class,
                    () -> context.services().require(MeshToolRegistry.class).register(tool()));
            assertEquals(MeshToolRegistry.class, failure.serviceType());
            assertEquals(dev.turboism.sdk.plugin.PluginService.MESH_TOOLS, failure.service());
            org.junit.jupiter.api.Assertions.assertFalse(
                    context.services().installed().contains(dev.turboism.sdk.plugin.PluginService.MESH_TOOLS));
        } finally {
            context.disposableScope().close();
            scheduler.shutdown();
        }
    }

    @Test
    void exactServiceInstallationIsOneShotAndRoutesTheSdkMethod() throws Exception {
        RuntimeScheduler scheduler = scheduler();
        CorePluginContext context = new CorePluginContext(dependencies(scheduler));
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        MeshToolRegistry service = service(registrations, closes);
        try {
            context.installMeshTools(service);
            assertSame(service, context.services().require(MeshToolRegistry.class));
            org.junit.jupiter.api.Assertions.assertTrue(
                    context.services().installed().contains(dev.turboism.sdk.plugin.PluginService.MESH_TOOLS));
            context.services().require(MeshToolRegistry.class).register(tool());
            assertEquals(1, registrations.get());
            assertThrows(
                    IllegalStateException.class,
                    () -> context.installMeshTools(service(new AtomicInteger(), new AtomicInteger())));
            context.disposableScope().seal();
            org.junit.jupiter.api.Assertions.assertNull(context.services().get(MeshToolRegistry.class));
            org.junit.jupiter.api.Assertions.assertFalse(
                    context.services().installed().contains(dev.turboism.sdk.plugin.PluginService.MESH_TOOLS));
            context.disposableScope().close();
            context.disposableScope().close();
            assertEquals(1, closes.get());
        } finally {
            context.disposableScope().close();
            scheduler.shutdown();
        }
    }

    private CorePluginContext.Dependencies dependencies(RuntimeScheduler scheduler) {
        return new CorePluginContext.Dependencies(
                descriptor(),
                logger(),
                paths(),
                uiScheduler(),
                scheduler,
                diagnostics(),
                new DisposableScope(),
                emptyHostSnapshotSource(),
                ignored -> {},
                CLOCK);
    }

    @Test
    void ordinaryToolsAreAnIndependentDirectoryServiceWithScopeOwnedCleanup() throws Exception {
        RuntimeScheduler scheduler = scheduler();
        CorePluginContext context = new CorePluginContext(dependencies(scheduler));
        AtomicInteger meshCloses = new AtomicInteger();
        AtomicInteger modelingCloses = new AtomicInteger();
        var ordinary = new dev.turboism.sdk.cubism.modeling.ModelingToolRegistry() {
            @Override
            public Registration register(
                    dev.turboism.sdk.cubism.modeling.ModelingTool tool,
                    dev.turboism.sdk.ui.toolbar.MainToolbarRegistry.Placement placement) {
                return () -> {};
            }

            @Override
            public void close() {
                modelingCloses.incrementAndGet();
            }
        };
        try {
            assertThrows(
                    dev.turboism.sdk.plugin.PluginServiceUnavailableException.class,
                    () -> context.services().require(dev.turboism.sdk.cubism.modeling.ModelingToolRegistry.class));
            context.installMeshTools(service(new AtomicInteger(), meshCloses));
            org.junit.jupiter.api.Assertions.assertNull(
                    context.services().get(dev.turboism.sdk.cubism.modeling.ModelingToolRegistry.class));
            context.installModelingTools(ordinary);
            assertSame(
                    ordinary, context.services().require(dev.turboism.sdk.cubism.modeling.ModelingToolRegistry.class));
            org.junit.jupiter.api.Assertions.assertTrue(
                    context.services().installed().contains(dev.turboism.sdk.plugin.PluginService.MODELING_TOOLS));
            assertThrows(IllegalStateException.class, () -> context.installModelingTools(ordinary));
            context.disposableScope().seal();
            org.junit.jupiter.api.Assertions.assertNull(
                    context.services().get(dev.turboism.sdk.cubism.modeling.ModelingToolRegistry.class));
            context.disposableScope().close();
            context.disposableScope().close();
            assertEquals(1, meshCloses.get());
            assertEquals(1, modelingCloses.get());
        } finally {
            context.disposableScope().close();
            scheduler.shutdown();
        }
    }

    private static MeshToolRegistry service(final AtomicInteger registrations, final AtomicInteger closes) {
        return new MeshToolRegistry() {
            @Override
            public Registration register(final MeshTool tool) {
                registrations.incrementAndGet();
                return () -> {};
            }

            @Override
            public Registration contributeSlider(final MeshToolbarSlider slider) {
                return () -> {};
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
    }

    private static MeshTool tool() {
        return new MeshTool() {
            @Override
            public String id() {
                return "mesh.select-brush";
            }

            @Override
            public String label() {
                return "Selection Brush";
            }

            @Override
            public String iconResourcePath() {
                return "icons/selection-brush.png";
            }

            @Override
            public void activate(MeshToolContext context) {}

            @Override
            public void deactivate() {}
        };
    }

    private static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override
            public String id() {
                return "dev.turboism.plugin.mesh-tools-test";
            }

            @Override
            public String name() {
                return "Mesh Tools Test";
            }

            @Override
            public String version() {
                return "0.1.0";
            }

            @Override
            public String description() {
                return "Mesh tools composition test";
            }

            @Override
            public List<String> entrypoints() {
                return List.of("dev.turboism.test.MeshToolsPlugin");
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
                return Optional.empty();
            }

            @Override
            public List<String> resources() {
                return List.of();
            }

            @Override
            public I18n i18n() {
                return new I18n() {
                    @Override
                    public String baseName() {
                        return "META-INF/turboism/i18n/messages";
                    }

                    @Override
                    public List<String> locales() {
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

    private PluginPaths paths() {
        return new PluginPaths() {
            @Override
            public Path dataDir() {
                return tempDir;
            }

            @Override
            public Path logsDir() {
                return tempDir;
            }

            @Override
            public Path stateDir() {
                return tempDir;
            }

            @Override
            public Path cacheDir() {
                return tempDir;
            }
        };
    }

    private static PluginLogger logger() {
        return new PluginLogger() {
            @Override
            public void debug(String message) {}

            @Override
            public void info(String message) {}

            @Override
            public void warn(String message) {}

            @Override
            public void error(String message) {}

            @Override
            public void error(String message, Throwable throwable) {}
        };
    }

    private static UiScheduler uiScheduler() {
        return new UiScheduler() {
            @Override
            public Registration runOnUiThread(Runnable work) {
                work.run();
                return () -> {};
            }

            @Override
            public Registration runOnUiThreadLater(Runnable work, Duration delay) {
                return () -> {};
            }
        };
    }

    private static DiagnosticReport diagnostics() {
        return new DiagnosticReport() {
            @Override
            public Instant createdAt() {
                return CLOCK.instant();
            }

            @Override
            public List<Problem> problems() {
                return List.of();
            }
        };
    }

    private static HostSnapshotSource emptyHostSnapshotSource() {
        return new HostSnapshotSource() {
            @Override
            public Optional<HostProject> activeProject() {
                return Optional.empty();
            }

            @Override
            public Optional<HostDocument> activeDocument() {
                return Optional.empty();
            }

            @Override
            public Optional<HostModel> activeModel() {
                return Optional.empty();
            }

            @Override
            public HostSelection selection() {
                return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
            }

            @Override
            public boolean isHostPresent() {
                return false;
            }

            @Override
            public long invalidationToken() {
                return 0L;
            }
        };
    }

    private static RuntimeScheduler scheduler() {
        List<PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        return new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 4, events::add, CLOCK),
                SidecarDispatcher.noop(),
                events::add);
    }
}
