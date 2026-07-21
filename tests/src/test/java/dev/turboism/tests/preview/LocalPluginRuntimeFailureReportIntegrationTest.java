package dev.turboism.preview;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.bootstrap.HostRuntimeIngress;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.preview.report.PreviewReportType;
import dev.turboism.preview.report.PreviewReportValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalPluginRuntimeFailureReportIntegrationTest {

    @TempDir
    Path temporary;

    @Test
    void realPluginJarWiresContextFailuresIntoInitialAndFinalReportsExactlyOnce() throws Exception {
        final Path home = temporary.resolve("preview-home");
        Files.createDirectories(home.resolve("plugins"));
        PreviewFailurePluginJarFixture.write(home.resolve("plugins"), temporary);

        final PreviewLog log = new PreviewLog(home.resolve("logs/turboism.log"));
        final RuntimeScheduler scheduler = rejectedScheduler();
        final HostRuntimeIngress ingress = new HostRuntimeIngress();
        final LocalPluginRuntime plugins = new LocalPluginRuntime(
            home,
            scheduler,
            ingress.adapterAccess(),
            log
        );
        final LocalPluginRuntime.LoadReport loadReport = plugins.loadAll();
        final PreviewRuntime runtime = runtime(home, log, scheduler, ingress, plugins, loadReport);
        try {
            runtime.writeInitialReports(HostSession.State.SAFE_MODE);
            assertTrue(Files.isRegularFile(home.resolve("plugins/preview-failure-plugin.jar")));
            assertEquals(List.of(), loadReport.failures());
            assertEquals(1, loadReport.loaded().size());
            assertEquals(PreviewFailurePluginJarFixture.PLUGIN_ID, loadReport.loaded().get(0).id());
            assertInitialReport(home);
            assertSafeLog(home);

            runtime.close();
            assertFinalReport(home);
            assertSafeLog(home);
        } finally {
            runtime.close();
            ingress.close();
            scheduler.shutdown();
            log.close();
        }
    }

    private static RuntimeScheduler rejectedScheduler() {
        return new RuntimeScheduler(
            task -> dev.turboism.sdk.plugin.WorkBudget.REJECTED,
            new PluginWorkExecutorRegistry(1, 4, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }

    private static PreviewRuntime runtime(
        final Path home,
        final PreviewLog log,
        final RuntimeScheduler scheduler,
        final HostRuntimeIngress ingress,
        final LocalPluginRuntime plugins,
        final LocalPluginRuntime.LoadReport loadReport
    ) {
        return new PreviewRuntime(
            home,
            log,
            scheduler,
            ingress,
            plugins,
            loadReport,
            new dev.turboism.preview.report.PreviewReportWriter(home.resolve("state"), ignored -> { }),
            "real-plugin-failure-runtime",
            null,
            null
        );
    }

    private static void assertInitialReport(final Path home) throws Exception {
        final JsonNode payload = report(home).path("payload");
        assertEquals("RUNNING", payload.path("runtimeState").textValue());
        assertFailures(payload, 1, 2, 2);
        assertSafe(payload);
    }

    private static void assertFinalReport(final Path home) throws Exception {
        final JsonNode payload = report(home).path("payload");
        assertEquals("STOPPED", payload.path("runtimeState").textValue());
        assertFailures(payload, 1, 2, 2);
        assertEquals(1, payload.path("shutdownCounts").path("attempted").longValue());
        assertEquals(1, payload.path("shutdownCounts").path("succeeded").longValue());
        assertSafe(payload);
    }

    private static JsonNode report(final Path home) throws Exception {
        return PreviewReportValidator.validate(Files.readAllBytes(
            home.resolve("state").resolve(PreviewReportType.PREVIEW_RUNTIME.fileName())
        )).document();
    }

    private static void assertFailures(
        final JsonNode payload,
        final long taskCount,
        final long storageCount,
        final long configCount
    ) {
        assertFailureArray(payload.path("taskFailures"), taskCount);
        assertFailureArray(payload.path("storageFailures"), storageCount);
        assertFailureArray(payload.path("configFailures"), configCount);
        assertEquals("TASK_REJECTED_POLICY_REJECTED", payload.path("taskFailures").get(0)
            .path("code").textValue());
        assertTrue(payload.path("storageFailures").toString().contains("PERMISSION_DENIED"));
        assertEquals(1, payload.path("configFailures").findValuesAsText("code").stream()
            .filter("SCHEMA_NOT_REGISTERED"::equals)
            .count());
        assertEquals(1, payload.path("configFailures").findValuesAsText("code").stream()
            .filter("CONFIG_READ_REJECTED"::equals)
            .count());
    }

    private static void assertFailureArray(final JsonNode failures, final long expectedCount) {
        assertEquals(
            expectedCount,
            java.util.stream.StreamSupport.stream(failures.spliterator(), false)
                .mapToLong(value -> value.path("count").longValue())
                .sum()
        );
    }

    private static void assertSafe(final JsonNode payload) {
        final String report = payload.toString();
        assertSafeText(report);
        assertEquals(1, occurrences(report, "TASK_REJECTED_POLICY_REJECTED"));
        assertEquals(2, occurrences(report, "PERMISSION_DENIED"));
        assertEquals(1, occurrences(report, "SCHEMA_NOT_REGISTERED"));
        assertEquals(1, occurrences(report, "CONFIG_READ_REJECTED"));
    }

    private static void assertSafeLog(final Path home) throws Exception {
        assertSafeText(Files.readString(home.resolve("logs/turboism.log")));
    }

    private static void assertSafeText(final String text) {
        assertFalse(text.contains(PreviewFailurePluginJarFixture.SECRET));
        assertFalse(text.contains("legacy.properties"));
        assertFalse(text.contains("storage.txt"));
        assertFalse(text.contains("preview-secret-must-not-leak-task"));
        assertFalse(text.contains("C:/"));
        assertFalse(text.contains("plugin-data"));
    }

    private static int occurrences(final String value, final String token) {
        int count = 0;
        int position = 0;
        while ((position = value.indexOf(token, position)) >= 0) {
            count++;
            position += token.length();
        }
        return count;
    }
}
