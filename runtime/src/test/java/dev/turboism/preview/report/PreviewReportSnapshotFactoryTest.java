package dev.turboism.preview.report;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.preview.LocalPluginRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PreviewReportSnapshotFactoryTest {

    @TempDir
    Path temporary;

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
                List.of()
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
}
