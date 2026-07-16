package dev.turboism.preview;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.descriptor.CorePluginDescriptor;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.core.plugin.PluginRuntime;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.hostread.SharedAsyncHostReadLane;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.preview.report.PreviewReportType;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.TurboismPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
            final ObjectNode report = previewRuntimeReport();
            PreviewRuntime.applyShutdownReportCounts(
                Map.of(PreviewReportType.PREVIEW_RUNTIME, report),
                summaries,
                true
            );
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

    private static ObjectNode previewRuntimeReport() {
        final ObjectNode report = JsonNodeFactory.instance.objectNode();
        final ObjectNode payload = report.putObject("payload");
        payload.putObject("shutdownCounts")
            .put("attempted", 0)
            .put("succeeded", 0)
            .put("failed", 0)
            .put("timedOut", 0);
        payload.putObject("cleanupCounts")
            .put("scopesClosed", 0)
            .put("classloadersClosed", 0)
            .put("failures", 0);
        return report;
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

    @SuppressWarnings("unchecked")
    private static void addLoaded(
        final LocalPluginRuntime runtime,
        final LoadedFixture fixture
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
            RuntimePluginLocalization.class
        );
        constructor.setAccessible(true);
        final Object value = constructor.newInstance(
            fixture.jar(),
            fixture.runtime(),
            fixture.plugin(),
            fixture.scope(),
            fixture.loader(),
            fixture.localization()
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
