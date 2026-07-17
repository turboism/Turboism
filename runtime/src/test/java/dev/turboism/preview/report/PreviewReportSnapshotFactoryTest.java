package dev.turboism.preview.report;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.failure.RuntimeFailure;
import dev.turboism.failure.RuntimeFailureSnapshot;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.preview.LocalPluginRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviewReportSnapshotFactoryTest {

    @TempDir
    Path temporary;

    @Test
    void writesFailureSnapshotIntoClosedRuntimeFailureArrays() {
        final RuntimeFailure task = failure("TASK_FAILED", "task", "task.run");
        final RuntimeFailure storage = failure("STORAGE_FAILED", "storage", "storage.readUtf8");
        final RuntimeFailure config = failure("CONFIG_FAILED", "config", "config.read");

        final var report = PreviewReportSnapshotFactory.create(
            "runtime-failures",
            Instant.parse("2026-07-15T00:00:00Z"),
            temporary,
            HostSession.State.ACTIVE,
            null,
            null,
            new LocalPluginRuntime.LoadReport(List.of(), List.of(), List.of()),
            List.of(),
            new RuntimeFailureSnapshot(List.of(task), List.of(storage), List.of(config)),
            false
        ).get(PreviewReportType.PREVIEW_RUNTIME);

        assertEquals("TASK_FAILED", report.path("payload").path("taskFailures").get(0)
            .path("code").textValue());
        assertEquals("STORAGE_FAILED", report.path("payload").path("storageFailures").get(0)
            .path("code").textValue());
        assertEquals("CONFIG_FAILED", report.path("payload").path("configFailures").get(0)
            .path("code").textValue());
        assertThrows(UnsupportedOperationException.class, () -> new RuntimeFailureSnapshot(
            List.of(task), List.of(storage), List.of(config)
        ).taskFailures().add(task));
    }

    @Test
    void activeHostNeverElevatesStaticVerificationRecordToRuntimeObserved() throws Exception {
        final Path verification = temporary.resolve("state/verification.json");
        Files.createDirectories(verification.getParent());
        Files.writeString(verification, "static verification record");
        final LocalPluginRuntime.LoadedPluginSummary plugin =
            new LocalPluginRuntime.LoadedPluginSummary(
                "dev.turboism.plugin.project-inspector",
                "Project Inspector",
                "1.0.0",
                PluginLifecycleState.ENABLED,
                temporary.resolve("plugins/project-inspector.jar"),
                List.of("cubism.project.read"),
                List.of("turboism.cubism.project.read"),
                new RuntimePluginLocalization.ReportSnapshot(
                    "dev.turboism.plugin.project-inspector",
                    "JVM_DISPLAY_DEFAULT",
                    "en-US",
                    "en-US",
                    List.of("en", "base", "marker"),
                    List.of(),
                    List.of(),
                    List.of(),
                    0,
                    0,
                    0,
                    0
                ),
                "NOT_STARTED",
                "NOT_STARTED",
                "NOT_STARTED",
                "NOT_STARTED",
                "NOT_STARTED",
                List.of(),
                CleanupEvidenceCollector.Snapshot.empty()
            );

        final Map<PreviewReportType, com.fasterxml.jackson.databind.node.ObjectNode> reports =
            PreviewReportSnapshotFactory.create(
                "runtime-snapshot-test",
                Instant.parse("2026-07-15T00:00:00Z"),
                temporary,
                HostSession.State.ACTIVE,
                temporary.resolve("Cubism.exe"),
                verification,
                new LocalPluginRuntime.LoadReport(List.of(plugin), List.of(), List.of()),
                List.of(plugin),
                false
            );

        final JsonNode evidence = reports.get(PreviewReportType.CAPABILITY)
            .path("payload")
            .path("capabilities")
            .get(0)
            .path("evidence")
            .get(0);
        assertEquals("STATIC_VERIFIED", evidence.path("kind").textValue());
        assertEquals("AVAILABLE", evidence.path("state").textValue());
    }

    private static RuntimeFailure failure(
        final String code,
        final String phase,
        final String operationId
    ) {
        return new RuntimeFailure(
            code,
            "ERROR",
            phase,
            "dev.example.plugin",
            operationId,
            null,
            "Runtime operation failed safely.",
            null,
            1
        );
    }
}
