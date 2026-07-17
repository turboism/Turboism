package dev.turboism.preview.report;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.core.lifecycle.PluginLifecycleState;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class PreviewReportSnapshotFactoryTest {

    @TempDir
    Path temporary;

    @Test
    void capabilityReportUsesClosedExplicitBindingsForOfficialDescriptors() throws Exception {
        final LocalPluginRuntime.LoadedPluginSummary clipMask = plugin(
            "dev.turboism.plugin.clipmask",
            List.of("cubism.clipmask.read"),
            List.of("turboism.cubism.model.read")
        );
        final LocalPluginRuntime.LoadedPluginSummary parameter = plugin(
            "dev.turboism.plugin.parameter",
            List.of("cubism.parameter.read", "cubism.parameter.write"),
            List.of("turboism.cubism.model.read", "turboism.cubism.model.write")
        );

        final JsonNode capabilities = capabilityReport(List.of(clipMask, parameter), HostSession.State.SAFE_MODE, false)
            .path("payload")
            .path("capabilities");

        assertCapability(
            capabilities,
            "dev.turboism.plugin.clipmask",
            "cubism.clipmask.read",
            "cubismRead.clipMasks",
            "turboism.cubism.model.read"
        );
        assertCapability(
            capabilities,
            "dev.turboism.plugin.parameter",
            "cubism.parameter.read",
            "parameterQuery.listAll",
            "turboism.cubism.model.read"
        );
        assertCapability(
            capabilities,
            "dev.turboism.plugin.parameter",
            "cubism.parameter.write",
            "cubism.parameter.write",
            "turboism.cubism.model.write"
        );
    }

    @Test
    void everyOfficialDescriptorCapabilityHasAnExplicitClosedBinding() throws Exception {
        final List<LocalPluginRuntime.LoadedPluginSummary> plugins = List.of(
            plugin(
                "dev.turboism.plugin.mesh",
                List.of("cubism.mesh.read", "cubism.deformer.read", "ui.context-source.read", "ui.status.notify"),
                List.of(
                    "turboism.cubism.model.read",
                    "turboism.ui.context-source.read",
                    "turboism.ui.status.notify"
                )
            ),
            plugin(
                "dev.turboism.plugin.parameter",
                List.of("cubism.parameter.read", "cubism.parameter.write", "ui.file-chooser.request", "ui.status.notify"),
                List.of(
                    "turboism.cubism.model.read",
                    "turboism.cubism.model.write",
                    "turboism.ui.file-chooser.request",
                    "turboism.ui.status.notify"
                )
            ),
            plugin(
                "dev.turboism.plugin.project-inspector",
                List.of("cubism.project.read", "cubism.workspace.read"),
                List.of("turboism.cubism.project.read")
            ),
            plugin(
                "dev.turboism.plugin.renderopt",
                List.of("cubism.render.status.read", "ui.overlay.contribute", "ui.status.notify"),
                List.of(
                    "turboism.cubism.model.read",
                    "turboism.ui.overlay.contribute",
                    "turboism.ui.status.notify"
                )
            ),
            plugin(
                "dev.turboism.plugin.clipmask",
                List.of("cubism.clipmask.read", "ui.dialog.contribute", "ui.embedded-panel.contribute", "ui.status.notify"),
                List.of(
                    "turboism.cubism.model.read",
                    "turboism.ui.dialog.contribute",
                    "turboism.ui.panel.contribute",
                    "turboism.ui.status.notify"
                )
            )
        );

        final JsonNode capabilities = capabilityReport(plugins, HostSession.State.SAFE_MODE, false)
            .path("payload")
            .path("capabilities");

        for (JsonNode capability : capabilities) {
            assertFalse(capability.path("operationId").asText().isBlank());
            assertFalse(capability.path("permissionId").asText().isBlank());
            assertEquals("GRANTED", capability.path("permissionAvailability").textValue());
        }
    }

    @Test
    void unknownCapabilityNeverUsesPrefixOrDescriptorFieldsAsBindingEvidence() throws Exception {
        final LocalPluginRuntime.LoadedPluginSummary plugin = plugin(
            "dev.turboism.plugin.unknown",
            List.of("cubism.parameter.future"),
            List.of("turboism.cubism.parameter.future")
        );

        final JsonNode capability = capabilityReport(List.of(plugin), HostSession.State.ACTIVE, false)
            .path("payload")
            .path("capabilities")
            .get(0);

        assertEquals("cubism.parameter.future", capability.path("operationId").textValue());
        assertFalse(capability.has("permissionId"));
        assertEquals("UNKNOWN", capability.path("permissionAvailability").textValue());
        assertEquals("UNKNOWN", capability.path("capabilityAvailability").textValue());
        assertEquals("DECLARED", capability.path("evidence").get(0).path("kind").textValue());
        assertEquals("UNKNOWN", capability.path("evidence").get(0).path("state").textValue());
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

    private JsonNode capabilityReport(
        final List<LocalPluginRuntime.LoadedPluginSummary> plugins,
        final HostSession.State hostState,
        final boolean stopped
    ) throws Exception {
        final Path verification = temporary.resolve("state/verification.json");
        Files.createDirectories(verification.getParent());
        Files.writeString(verification, "static verification record");
        return PreviewReportSnapshotFactory.create(
            "runtime-snapshot-test",
            Instant.parse("2026-07-15T00:00:00Z"),
            temporary,
            hostState,
            temporary.resolve("Cubism.exe"),
            verification,
            new LocalPluginRuntime.LoadReport(plugins, List.of(), List.of()),
            plugins,
            stopped
        ).get(PreviewReportType.CAPABILITY);
    }

    private LocalPluginRuntime.LoadedPluginSummary plugin(
        final String pluginId,
        final List<String> capabilities,
        final List<String> permissions
    ) {
        return new LocalPluginRuntime.LoadedPluginSummary(
            pluginId,
            pluginId,
            "1.0.0",
            PluginLifecycleState.ENABLED,
            temporary.resolve("plugins/" + pluginId + ".jar"),
            capabilities,
            permissions,
            new RuntimePluginLocalization.ReportSnapshot(
                pluginId,
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
    }

    private static void assertCapability(
        final JsonNode capabilities,
        final String pluginId,
        final String capabilityId,
        final String operationId,
        final String permissionId
    ) {
        JsonNode found = null;
        for (JsonNode capability : capabilities) {
            if (pluginId.equals(capability.path("pluginId").textValue())
                && capabilityId.equals(capability.path("capabilityId").textValue())) {
                found = capability;
                break;
            }
        }
        assertNotNull(found, "missing capability " + pluginId + "/" + capabilityId);
        assertEquals(operationId, found.path("operationId").textValue());
        assertEquals(permissionId, found.path("permissionId").textValue());
    }
}
