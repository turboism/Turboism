package dev.turboism.core.plugin.context;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.adapter.cubism.HostSnapshotSource;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.psd.PsdExportHost;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.RawImageId;
import dev.turboism.sdk.cubism.model.AtlasTexture;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelImageGroup;
import dev.turboism.sdk.cubism.model.ModelTextures;
import dev.turboism.sdk.cubism.model.RawTexture;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.PluginPaths;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import dev.turboism.task.RuntimePluginTaskScheduler;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DefaultCubismServicesFactoryPsdWiringTest {
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-09T00:00:00Z"), ZoneOffset.UTC);
    private static final String PLUGIN_ID = "test.psd-wiring";
    private static final RawImageId SOURCE = new RawImageId("raw-source");

    @Test
    void factoryWithPluginTasksRoutesExportThroughRuntimeService() throws Exception {
        final WiringTextures textures = new WiringTextures();
        final DisposableScope scope = new DisposableScope();
        final RuntimeScheduler scheduler = scheduler();
        try {
            final RuntimePluginTaskScheduler pluginTasks = new RuntimePluginTaskScheduler(PLUGIN_ID, scheduler, scope);
            final DefaultCubismServicesFactory factory = DefaultCubismServicesFactoryTestSupport.withModelAccess(
                    RuntimeHostAdapters.safeMode(), () -> model(textures));
            final CorePluginContext.Dependencies dependencies = dependencies(scope, scheduler);

            final PsdExportResult result = await(factory.create(dependencies, pluginTasks)
                    .cubismFacade()
                    .model()
                    .active()
                    .textures()
                    .exportRawImagePsd(SOURCE));

            assertEquals(PsdExportResult.Status.UNAVAILABLE, result.status());
            assertTrue(result.file().isEmpty());
            assertTrue(result.initialRevision().isEmpty());
            assertEquals(1, textures.nativeCalls.get());
            assertEquals(
                    "PSD_NATIVE_EXPORT;status=UNAVAILABLE;integrity=UNAVAILABLE;readable=false;structure=false",
                    result.diagnostic());
        } finally {
            scope.close();
            if (!scheduler.isClosed()) {
                scheduler.shutdown();
            }
        }
    }

    @Test
    void factoryWithoutPluginTasksKeepsDefaultTypedUnavailablePath() throws Exception {
        final WiringTextures textures = new WiringTextures();
        final DisposableScope scope = new DisposableScope();
        final RuntimeScheduler scheduler = scheduler();
        try {
            final DefaultCubismServicesFactory factory = DefaultCubismServicesFactoryTestSupport.withModelAccess(
                    RuntimeHostAdapters.safeMode(), () -> model(textures));
            final CorePluginContext.Dependencies dependencies = dependencies(scope, scheduler);

            final PsdExportResult result = await(factory.create(dependencies)
                    .cubismFacade()
                    .model()
                    .active()
                    .textures()
                    .exportRawImagePsd(SOURCE));

            assertEquals(PsdExportResult.Status.UNAVAILABLE, result.status());
            assertTrue(result.file().isEmpty());
            assertTrue(result.initialRevision().isEmpty());
            assertEquals(0, textures.nativeCalls.get());
        } finally {
            scope.close();
            if (!scheduler.isClosed()) {
                scheduler.shutdown();
            }
        }
    }

    private static PsdExportResult await(final CompletionStage<PsdExportResult> stage) throws Exception {
        return stage.toCompletableFuture().get(3, TimeUnit.SECONDS);
    }

    private static CubismModel model(final WiringTextures textures) {
        return new CubismModel() {
            @Override
            public ModelId id() {
                return new ModelId("model-a");
            }

            @Override
            public ModelTextures textures() {
                return textures;
            }

            @Override
            public dev.turboism.sdk.cubism.model.Parameters parameters() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Parts parts() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Drawables drawables() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Deformers deformers() {
                throw new UnsupportedOperationException();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Glues glues() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void update() {}
        };
    }

    private static CorePluginContext.Dependencies dependencies(
            final DisposableScope scope, final RuntimeScheduler scheduler) {
        return new CorePluginContext.Dependencies(
                descriptor(),
                logger(),
                paths(),
                uiScheduler(),
                scheduler,
                diagnostics(),
                scope,
                new dev.turboism.adapter.cubism.HostSnapshotSource() {
                    @Override
                    public Optional<HostSnapshotSource.HostProject> activeProject() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<HostSnapshotSource.HostDocument> activeDocument() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<HostSnapshotSource.HostModel> activeModel() {
                        return Optional.empty();
                    }

                    @Override
                    public HostSnapshotSource.HostSelection selection() {
                        return new HostSnapshotSource.HostSelection(
                                List.of(), Optional.empty(), Optional.empty(), Optional.empty());
                    }

                    @Override
                    public boolean isHostPresent() {
                        return false;
                    }

                    @Override
                    public long invalidationToken() {
                        return 0L;
                    }
                },
                ignored -> {},
                CLOCK);
    }

    private static PluginDescriptor descriptor() {
        return new PluginDescriptor() {
            @Override
            public String id() {
                return PLUGIN_ID;
            }

            @Override
            public String name() {
                return "PSD Wiring";
            }

            @Override
            public String version() {
                return "0.1.0";
            }

            @Override
            public String description() {
                return "Test";
            }

            @Override
            public List<String> entrypoints() {
                return List.of("dev.turboism.test.PsdWiringPlugin");
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
                return List.of(
                        permission("turboism.cubism.model.read"),
                        permission("turboism.file.read"),
                        permission("turboism.file.write"));
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

    private static PluginDescriptor.PermissionRef permission(final String id) {
        return new PluginDescriptor.PermissionRef() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String scope() {
                return "application";
            }

            @Override
            public Optional<String> reason() {
                return Optional.empty();
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

    private static PluginPaths paths() {
        return new PluginPaths() {
            @Override
            public Path dataDir() {
                return Path.of(".");
            }

            @Override
            public Path logsDir() {
                return Path.of(".");
            }

            @Override
            public Path stateDir() {
                return Path.of(".");
            }

            @Override
            public Path cacheDir() {
                return Path.of(".");
            }
        };
    }

    private static UiScheduler uiScheduler() {
        return new UiScheduler() {
            @Override
            public Registration runOnUiThread(final Runnable work) {
                work.run();
                return () -> {};
            }

            @Override
            public Registration runOnUiThreadLater(final Runnable work, final Duration delay) {
                return () -> {};
            }
        };
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 4, ignored -> {}, CLOCK),
                SidecarDispatcher.noop(),
                ignored -> {});
    }

    private static dev.turboism.sdk.diagnostics.DiagnosticReport diagnostics() {
        return new dev.turboism.sdk.diagnostics.DiagnosticReport() {
            @Override
            public Instant createdAt() {
                return CLOCK.instant();
            }

            @Override
            public List<dev.turboism.sdk.diagnostics.DiagnosticReport.Problem> problems() {
                return List.of();
            }
        };
    }

    private static final class WiringTextures implements ModelTextures, PsdExportHost {
        private final AtomicInteger nativeCalls = new AtomicInteger();

        @Override
        public List<RawTexture> rawImages() {
            return List.of();
        }

        @Override
        public List<ModelImageGroup> modelImageGroups() {
            return List.of();
        }

        @Override
        public List<AtlasTexture> textureAtlases() {
            return List.of();
        }

        @Override
        public Observation exportPsdTo(final RawImageId source, final Path destination, final Runnable admission) {
            admission.run();
            nativeCalls.incrementAndGet();
            return Observation.unavailable();
        }

        @Override
        public void addModelImageGroup(final String name) {}

        @Override
        public void removeModelImage(final dev.turboism.sdk.cubism.id.ModelImageId id) {}

        @Override
        public dev.turboism.sdk.cubism.id.TextureAtlasId addTextureAtlas(
                final String name, final int widthPixels, final int heightPixels) {
            return new dev.turboism.sdk.cubism.id.TextureAtlasId("atlas");
        }

        @Override
        public void removeTextureAtlas(final dev.turboism.sdk.cubism.id.TextureAtlasId id) {}

        @Override
        public void removeRawImage(final RawImageId id) {}
    }
}
