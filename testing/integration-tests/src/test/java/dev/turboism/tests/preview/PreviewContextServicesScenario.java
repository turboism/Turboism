package dev.turboism.tests.preview;

import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.preview.LocalPluginRuntime;
import dev.turboism.preview.PreviewLog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Exercises the real fixture JAR against the preview PluginContext service graph. */
final class PreviewContextServicesScenario {

    private PreviewContextServicesScenario() {
    }

    static void verify(final Path temporaryDirectory) throws Exception {
        final Path home = temporaryDirectory.resolve("preview-context-services-home");
        final Path markerDirectory = temporaryDirectory.resolve("preview-context-services-marker");
        final String property = PreviewContextServicesPluginJarFixture.MARKER_DIRECTORY_PROPERTY;
        final String previousMarkerDirectory = System.getProperty(property);
        System.setProperty(property, markerDirectory.toString());
        try {
            loadAndAssert(home, markerDirectory, temporaryDirectory);
        } finally {
            restoreProperty(property, previousMarkerDirectory);
        }
    }

    private static void loadAndAssert(
        final Path home,
        final Path markerDirectory,
        final Path temporaryDirectory
    ) throws Exception {
        final PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"));
        final RuntimeScheduler scheduler = scheduler();
        final HostRuntimeIngress hostIngress = new HostRuntimeIngress();
        final LocalPluginRuntime runtime = new LocalPluginRuntime(
            home, scheduler, hostIngress.adapterAccess(), log
        );
        try {
            PreviewContextServicesPluginJarFixture.write(home.resolve("plugins"), temporaryDirectory);
            assertResults(runtime.loadAll(), PreviewContextServicesPluginJarFixture.readyFile(markerDirectory));
        } finally {
            close(runtime, hostIngress, scheduler, log);
        }
    }

    private static void assertResults(
        final LocalPluginRuntime.LoadReport report,
        final Path ready
    ) throws Exception {
        awaitReady(ready);
        assertTrue(report.failures().isEmpty());
        assertEquals(2, report.loaded().size());
        assertEquals(PreviewContextServicesPluginJarFixture.PLUGIN_ID, report.loaded().stream().filter(plugin -> !plugin.id().equals("turboism.core")).findFirst().orElseThrow().id());
        assertEquals("ENABLED", report.loaded().stream().filter(plugin -> !plugin.id().equals("turboism.core")).findFirst().orElseThrow().state().name());
        assertEquals(PreviewContextServicesPluginJarFixture.EXPECTED_MARKER_VALUES, readMarker(ready));
    }

    private static void close(
        final LocalPluginRuntime runtime,
        final HostRuntimeIngress hostIngress,
        final RuntimeScheduler scheduler,
        final PreviewLog log
    ) throws IOException {
        try {
            runtime.close();
        } finally {
            hostIngress.close();
            scheduler.shutdown();
            log.close();
        }
    }

    private static void awaitReady(final Path ready) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.isRegularFile(ready) && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(Files.isRegularFile(ready), "fixture plugin did not publish its ready marker");
    }

    private static Map<String, String> readMarker(final Path ready) throws IOException {
        final Map<String, String> values = new LinkedHashMap<>();
        for (String line : Files.readAllLines(ready)) {
            addMarkerValue(values, line);
        }
        return Map.copyOf(values);
    }

    private static void addMarkerValue(final Map<String, String> values, final String line) {
        final int separator = line.indexOf('=');
        assertTrue(separator > 0, "marker line must contain a key and value");
        final String key = line.substring(0, separator);
        assertTrue(!values.containsKey(key), "marker must not repeat " + key);
        values.put(key, line.substring(separator + 1));
    }

    private static void restoreProperty(final String property, final String previousValue) {
        if (previousValue == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previousValue);
        }
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 16, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(), ignored -> { }
        );
    }
}
