package dev.turboism.preview;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.config.RuntimeTypedPluginConfigRegistry;
import dev.turboism.core.descriptor.CorePluginDescriptor;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.plugin.PluginRuntime;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.hostread.SharedAsyncHostReadLane;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.preview.report.PreviewReportSnapshotFactory;
import dev.turboism.preview.report.PreviewReportType;
import dev.turboism.sdk.config.ConfigCodecs;
import dev.turboism.sdk.config.ConfigKey;
import dev.turboism.sdk.config.ConfigSchema;
import dev.turboism.sdk.config.PluginConfigException;
import dev.turboism.sdk.config.PluginConfigRegistry;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.storage.StoragePath;
import dev.turboism.sdk.storage.StorageRoot;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskKind;
import dev.turboism.sdk.task.PluginTaskPriority;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.storage.RuntimePluginStorage;
import dev.turboism.task.RuntimePluginTaskScheduler;
import dev.turboism.userfile.RuntimeUserFileAccessService;
import dev.turboism.userfile.UserFileGrantSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPluginRuntimeCloseTest {

    @TempDir
    Path temporary;

    @Test
    void closeAttemptsEveryStageRetainsUnsafeLoaderAndClosesSharedLaneLast() throws Exception {
        final List<String> order = new ArrayList<>();
        final RuntimeScheduler scheduler = scheduler();
        final HostSession hostSession = new HostSession(Optional::empty);
        final Path logFile = temporary.resolve("logs/turboism.log");
        try (PreviewLog log = new PreviewLog(logFile)) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                temporary, scheduler, hostSession, log
            );
            final SharedAsyncHostReadLane lane = hostReadLane(runtime);
            final TrackingClassLoader healthyLoader = new TrackingClassLoader(
                "healthy", lane, order
            );
            final TrackingClassLoader failingLoader = new TrackingClassLoader(
                "failing", lane, order
            );

            addLoaded(runtime, loaded(
                "dev.example.healthy",
                new RecordingPlugin("healthy", order, false),
                scope("healthy", order, false),
                healthyLoader
            ));
            addLoaded(runtime, loaded(
                "dev.example.failing",
                new RecordingPlugin("failing", order, true),
                scope("failing", order, true),
                failingLoader
            ));

            runtime.close();

            assertTrue(order.contains("failing-disable"));
            assertTrue(order.contains("failing-shutdown"));
            assertTrue(order.contains("failing-scope"));
            assertTrue(order.contains("healthy-disable"));
            assertTrue(order.contains("healthy-shutdown"));
            assertTrue(order.contains("healthy-scope"));
            assertTrue(order.contains("healthy-loader:false"));
            assertTrue(
                order.indexOf("healthy-disable") > order.indexOf("failing-scope"),
                "one plugin failure must not prevent the next plugin close"
            );
            assertFalse(order.stream().anyMatch(value -> value.startsWith("failing-loader")));
            assertTrue(lane.isClosed());

            final List<LocalPluginRuntime.LoadedPluginSummary> summaries = runtime.reportSummaries();
            final LocalPluginRuntime.LoadedPluginSummary failing = summaries.stream()
                .filter(summary -> summary.id().equals("dev.example.failing"))
                .findFirst()
                .orElseThrow();
            assertEquals("FAILED", failing.scopeCleanupState());
            assertEquals(
                "NOT_STARTED",
                failing.classloaderCleanupState(),
                "a failed scope must retain the ClassLoader without reporting a close attempt"
            );
            assertEquals(
                List.of(
                    "PLUGIN_DISABLE_FAILED",
                    "PLUGIN_SHUTDOWN_FAILED",
                    "PLUGIN_SCOPE_CLEANUP_FAILED",
                    "PLUGIN_CLASSLOADER_RETAINED"
                ),
                failing.failures().stream()
                    .map(LocalPluginRuntime.PluginSummaryFailure::code)
                    .toList()
            );
            assertTrue(failing.failures().stream().allMatch(failure ->
                !failure.message().contains("private")
                    && !failure.message().contains("C:/Users")
            ));
            assertEquals(
                new PreviewRuntime.ShutdownReportCounts(2, 1, 1, 1, 1, 1),
                PreviewRuntime.shutdownReportCounts(summaries),
                "report counts must reflect attempted shutdown and cleanup stages"
            );
            final ObjectNode report = previewRuntimeReport(summaries);
            assertEquals(2, report.path("payload").path("shutdownCounts").path("attempted").asInt());
            assertEquals(1, report.path("payload").path("shutdownCounts").path("succeeded").asInt());
            assertEquals(1, report.path("payload").path("shutdownCounts").path("failed").asInt());
            assertEquals(1, report.path("payload").path("cleanupCounts").path("scopesClosed").asInt());
            assertEquals(
                1,
                report.path("payload").path("cleanupCounts").path("classloadersClosed").asInt()
            );
            assertEquals(1, report.path("payload").path("cleanupCounts").path("failures").asInt());
        } finally {
            hostSession.close();
            scheduler.shutdown();
        }

        final String log = Files.readString(logFile);
        assertFalse(log.contains("private-disable-detail"));
        assertFalse(log.contains("private-shutdown-detail"));
        assertFalse(log.contains("C:/Users/private/scope"));
    }

    @Test
    void realPluginServicesReportExactCleanupEvidenceOnlyDuringScopeClose() throws Exception {
        final String pluginId = "dev.example.cleanup";
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        final RuntimeScheduler scheduler = scheduler();
        final HostSession hostSession = new HostSession(Optional::empty);
        final DisposableScope scope = new DisposableScope();
        final Path home = temporary.resolve("cleanup");
        final Path selected = home.resolve("selected.txt");
        Files.createDirectories(home);
        Files.writeString(selected, "selected");
        try (PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"))) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                home, scheduler, hostSession, log
            );
            final RuntimePluginTaskScheduler tasks = new RuntimePluginTaskScheduler(
                pluginId, scheduler, scope, evidence
            );
            final Map<StorageRoot, Path> roots = Map.of(
                StorageRoot.DATA, home.resolve("data"),
                StorageRoot.STATE, home.resolve("state"),
                StorageRoot.CACHE, home.resolve("cache")
            );
            final Set<String> permissions = Set.of(
                PermissionIds.TURBOISM_FILE_READ,
                PermissionIds.TURBOISM_FILE_WRITE,
                PermissionIds.TURBOISM_CONFIG_PLUGIN_READ,
                PermissionIds.TURBOISM_CONFIG_PLUGIN_WRITE,
                PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST
            );
            final RuntimePluginStorage storage = new RuntimePluginStorage(
                pluginId, roots, permissions, tasks, scope, evidence
            );
            final RuntimeTypedPluginConfigRegistry config =
                new RuntimeTypedPluginConfigRegistry(
                    new NoopLegacyRegistry(),
                    pluginId,
                    home.resolve("typed-config"),
                    permissions,
                    tasks,
                    scope,
                    evidence
                );
            final RuntimeUserFileAccessService userFiles =
                new RuntimeUserFileAccessService(
                    pluginId,
                    permissions,
                    UserFileGrantSource.fixedSelection(selected),
                    tasks,
                    scope,
                    evidence
                );

            final var ordinary = tasks.scheduleWithFixedDelay(new FixedDelayTaskRequest(
                new TaskId("ordinary-cancel"),
                PluginTaskKind.LOW_FREQUENCY_REFRESH,
                PluginTaskPriority.LOW,
                Duration.ofHours(1),
                Duration.ofHours(1),
                token -> { }
            ));
            assertTrue(ordinary.accepted());
            assertTrue(ordinary.handle().cancel());
            ordinary.handle().completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            tasks.awaitContinuationQuiescence();
            assertEquals(0, evidence.snapshot().taskHandlesCanceled());
            assertEquals(0, evidence.snapshot().taskCompletionsSettled());
            assertEquals(0, evidence.snapshot().pluginContinuationsDrained());

            final var lifecycleTask = tasks.scheduleWithFixedDelay(new FixedDelayTaskRequest(
                new TaskId("lifecycle-cancel"),
                PluginTaskKind.LOW_FREQUENCY_REFRESH,
                PluginTaskPriority.LOW,
                Duration.ofHours(1),
                Duration.ofHours(1),
                token -> { }
            ));
            assertTrue(lifecycleTask.accepted());
            lifecycleTask.handle().completion().thenRun(() -> { });

            Files.createDirectories(roots.get(StorageRoot.DATA));
            Files.writeString(roots.get(StorageRoot.DATA).resolve("source.txt"), "source");
            Files.writeString(roots.get(StorageRoot.DATA).resolve("target.txt"), "target");
            assertFalse(storage.copy(
                new StoragePath(StorageRoot.DATA, "source.txt"),
                new StoragePath(StorageRoot.DATA, "target.txt"),
                false
            ).toCompletableFuture().get(2, TimeUnit.SECONDS).changed());

            config.registerSchema(new ConfigSchema(
                "cleanup",
                "cleanup.cfg",
                1,
                List.of(new ConfigKey<>(
                    "cleanup", "enabled", true, ConfigCodecs.booleanValue()
                ))
            ), List.of()).toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertTrue(userFiles.request(new UserFileRequest(
                "cleanup-file",
                "Select cleanup file",
                List.of("txt"),
                UserFileMode.READ,
                UserFileLifetime.UNTIL_DISABLE
            )).toCompletableFuture().get(2, TimeUnit.SECONDS).handle().isPresent());

            final URLClassLoader loader = new URLClassLoader(
                new URL[0], LocalPluginRuntimeCloseTest.class.getClassLoader()
            );
            addLoaded(runtime, loaded(
                pluginId,
                new RecordingPlugin("cleanup", new ArrayList<>(), false),
                scope,
                loader
            ), evidence);

            runtime.close();
            final ObjectNode report = previewRuntimeReport(runtime.reportSummaries());
            for (String field : List.of(
                "taskHandlesCanceled",
                "taskCompletionsSettled",
                "pluginContinuationsDrained",
                "userFileHandlesRevoked",
                "configSchemasUnregistered",
                "temporaryFilesDeleted"
            )) {
                assertTrue(
                    report.path("payload").path("cleanupCounts").path(field).asInt() > 0,
                    field + " must come from a completed production cleanup action"
                );
            }
            final CleanupEvidenceCollector.Snapshot afterClose = evidence.snapshot();
            runtime.close();
            assertEquals(afterClose, evidence.snapshot());
        } finally {
            try {
                scope.close();
            } finally {
                hostSession.close();
                scheduler.shutdown();
            }
        }
    }

    @Test
    void escapedPluginCloseAndFailingFallbacksDoNotBlockNextPluginOrSharedLane() throws Exception {
        final List<String> order = new ArrayList<>();
        final RuntimeScheduler scheduler = scheduler();
        final HostSession hostSession = new HostSession(Optional::empty);
        final Path logFile = temporary.resolve("fallback/logs/turboism.log");
        try (PreviewLog log = new PreviewLog(logFile)) {
            final LocalPluginRuntime runtime = new LocalPluginRuntime(
                temporary.resolve("fallback"),
                scheduler,
                hostSession,
                log,
                (pluginId, phase) -> {
                    order.add(pluginId + "-" + phase);
                    if (pluginId.equals("dev.example.failing")
                        && (phase.equals("close")
                            || phase.equals("fallback-summary")
                            || phase.equals("fallback-log"))) {
                        throw new AssertionError(
                            "C:/Users/private/outer-close-" + phase
                        );
                    }
                }
            );
            final SharedAsyncHostReadLane lane = hostReadLane(runtime);
            final TrackingClassLoader healthyLoader = new TrackingClassLoader(
                "healthy", lane, order
            );
            final TrackingClassLoader failingLoader = new TrackingClassLoader(
                "failing", lane, order
            );

            addLoaded(runtime, loaded(
                "dev.example.healthy",
                new RecordingPlugin("healthy", order, false),
                scope("healthy", order, false),
                healthyLoader
            ));
            addLoaded(runtime, loaded(
                "dev.example.failing",
                new RecordingPlugin("failing", order, false),
                scope("failing", order, false),
                failingLoader
            ));

            runtime.close();

            assertTrue(order.contains("dev.example.failing-close"));
            assertTrue(order.contains("dev.example.failing-fallback-summary"));
            assertTrue(order.contains("dev.example.failing-fallback-log"));
            assertTrue(order.contains("dev.example.healthy-close"));
            assertTrue(order.contains("healthy-disable"));
            assertTrue(order.contains("healthy-shutdown"));
            assertTrue(order.contains("healthy-scope"));
            assertTrue(order.contains("healthy-loader:false"));
            assertTrue(
                order.indexOf("dev.example.healthy-close")
                    > order.indexOf("dev.example.failing-fallback-log"),
                "fallback failures must not prevent the next plugin close"
            );
            assertTrue(lane.isClosed(), "shared host-read lane must always close");

            final List<LocalPluginRuntime.LoadedPluginSummary> summaries = runtime.reportSummaries();
            final LocalPluginRuntime.LoadedPluginSummary failing = summaries.stream()
                .filter(summary -> summary.id().equals("dev.example.failing"))
                .findFirst()
                .orElseThrow();
            assertEquals("NOT_STARTED", failing.disableState());
            assertEquals("NOT_STARTED", failing.shutdownState());
            assertEquals("NOT_STARTED", failing.unloadState());
            assertEquals("NOT_STARTED", failing.scopeCleanupState());
            assertEquals("NOT_STARTED", failing.classloaderCleanupState());
            assertEquals(
                List.of("PLUGIN_CLOSE_STAGE_FAILED"),
                failing.failures().stream()
                    .map(LocalPluginRuntime.PluginSummaryFailure::code)
                    .toList()
            );
            assertFalse(failing.failures().get(0).message().contains("private"));
            assertEquals(
                new PreviewRuntime.ShutdownReportCounts(2, 1, 1, 1, 1, 0),
                PreviewRuntime.shutdownReportCounts(summaries),
                "a failed plugin close attempt must not invent cleanup attempts"
            );
        } finally {
            hostSession.close();
            scheduler.shutdown();
        }

        final String log = Files.readString(logFile);
        assertFalse(log.contains("outer-close"));
        assertFalse(log.contains("C:/Users/private"));
    }

    private ObjectNode previewRuntimeReport(
        final List<LocalPluginRuntime.LoadedPluginSummary> summaries
    ) {
        return PreviewReportSnapshotFactory.create(
            "runtime-test",
            Instant.EPOCH,
            temporary,
            HostSession.State.CLOSED,
            null,
            null,
            new LocalPluginRuntime.LoadReport(List.of(), List.of(), List.of()),
            summaries,
            true
        ).get(PreviewReportType.PREVIEW_RUNTIME);
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginExecutorRegistry(1, 4, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    private static DisposableScope scope(
        final String id,
        final List<String> order,
        final boolean fail
    ) {
        final DisposableScope scope = new DisposableScope();
        scope.register(() -> {
            order.add(id + "-scope");
            if (fail) {
                throw new AssertionError("C:/Users/private/scope");
            }
        });
        return scope;
    }

    private static LoadedFixture loaded(
        final String id,
        final TurboismPlugin plugin,
        final DisposableScope scope,
        final URLClassLoader loader
    ) {
        final PluginDescriptor descriptor = new CorePluginDescriptor(
            id,
            id,
            "1.0.0",
            "test",
            Map.of(),
            "0.1.0",
            List.of(),
            "MIT",
            Optional.empty(),
            List.of(),
            List.of(),
            List.of(),
            new CorePluginDescriptor.CoreEnvironment(false, "none")
        );
        final PluginRuntime pluginRuntime = new PluginRuntime(id, descriptor);
        pluginRuntime.transitionTo(PluginLifecycleState.ENABLED);
        final RuntimePluginLocalization localization = RuntimePluginLocalization.create(
            id,
            loader,
            null,
            Locale.US,
            Locale.US,
            ignored -> { }
        );
        return new LoadedFixture(
            Path.of("plugins/" + id + ".jar"),
            pluginRuntime,
            plugin,
            scope,
            loader,
            localization
        );
    }

    private static void addLoaded(
        final LocalPluginRuntime runtime,
        final LoadedFixture fixture
    ) throws Exception {
        addLoaded(runtime, fixture, new CleanupEvidenceCollector());
    }

    @SuppressWarnings("unchecked")
    private static void addLoaded(
        final LocalPluginRuntime runtime,
        final LoadedFixture fixture,
        final CleanupEvidenceCollector cleanupEvidence
    ) throws Exception {
        final Class<?> type = Class.forName(
            "dev.turboism.preview.LocalPluginRuntime$LoadedPlugin"
        );
        final Constructor<?> constructor = type.getDeclaredConstructor(
            Path.class,
            PluginRuntime.class,
            TurboismPlugin.class,
            DisposableScope.class,
            URLClassLoader.class,
            RuntimePluginLocalization.class,
            CleanupEvidenceCollector.class
        );
        constructor.setAccessible(true);
        final Object value = constructor.newInstance(
            fixture.jar(),
            fixture.runtime(),
            fixture.plugin(),
            fixture.scope(),
            fixture.loader(),
            fixture.localization(),
            cleanupEvidence
        );
        final Field field = LocalPluginRuntime.class.getDeclaredField("loaded");
        field.setAccessible(true);
        ((List<Object>) field.get(runtime)).add(value);
    }

    private static SharedAsyncHostReadLane hostReadLane(
        final LocalPluginRuntime runtime
    ) throws Exception {
        final Field field = LocalPluginRuntime.class.getDeclaredField("hostReadLane");
        field.setAccessible(true);
        return (SharedAsyncHostReadLane) field.get(runtime);
    }

    private static final class RecordingPlugin implements TurboismPlugin {
        private final String id;
        private final List<String> order;
        private final boolean fail;

        private RecordingPlugin(
            final String id,
            final List<String> order,
            final boolean fail
        ) {
            this.id = id;
            this.order = order;
            this.fail = fail;
        }

        @Override
        public void disable() {
            order.add(id + "-disable");
            if (fail) {
                throw new AssertionError("private-disable-detail");
            }
        }

        @Override
        public void shutdown() {
            order.add(id + "-shutdown");
            if (fail) {
                throw new AssertionError("private-shutdown-detail");
            }
        }
    }

    private static final class TrackingClassLoader extends URLClassLoader {
        private final String id;
        private final SharedAsyncHostReadLane lane;
        private final List<String> order;

        private TrackingClassLoader(
            final String id,
            final SharedAsyncHostReadLane lane,
            final List<String> order
        ) {
            super(new URL[0], LocalPluginRuntimeCloseTest.class.getClassLoader());
            this.id = id;
            this.lane = lane;
            this.order = order;
        }

        @Override
        public void close() throws java.io.IOException {
            order.add(id + "-loader:" + lane.isClosed());
            super.close();
        }
    }

    private static final class NoopLegacyRegistry implements PluginConfigRegistry {
        @Override public Registration readScope(String relativePath) { return () -> { }; }
        @Override public Registration writeScope(String relativePath) { return () -> { }; }
        @Override public Optional<String> readString(String relativePath, String key) {
            return Optional.empty();
        }
        @Override public void writeString(String relativePath, String key, String value)
            throws PluginConfigException { }
    }

    private record LoadedFixture(
        Path jar,
        PluginRuntime runtime,
        TurboismPlugin plugin,
        DisposableScope scope,
        URLClassLoader loader,
        RuntimePluginLocalization localization
    ) {
    }
}
