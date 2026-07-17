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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void capabilityReportUsesClosedExplicitBindingsForOfficialDescriptors() throws Exception {
        final LocalPluginRuntime.LoadedPluginSummary clipMask = plugin(
            "dev.turboism.plugin.clipmask",
            List.of("cubism.clipmask.read"),
            List.of("turboism.cubism.model.read")
        );
        final LocalPluginRuntime.LoadedPluginSummary parameter = plugin(
            "dev.turboism.plugin.parameter",
            List.of("cubism.parameter.read", "cubism.parameter.write"),
            List.of(
                "turboism.cubism.model.read",
                "turboism.cubism.parameter.read",
                "turboism.cubism.model.write"
            )
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
        assertCapabilityBindings(
            capabilities,
            "dev.turboism.plugin.parameter",
            "cubism.parameter.read",
            Map.of(
                "cubismRead.parameters", "turboism.cubism.model.read",
                "parameterQuery.findById", "turboism.cubism.parameter.read",
                "parameterQuery.listAll", "turboism.cubism.parameter.read",
                "parameterQuery.exists", "turboism.cubism.parameter.read"
            )
        );
        assertCapabilityBindings(
            capabilities,
            "dev.turboism.plugin.parameter",
            "cubism.parameter.write",
            Map.of(
                "transaction.open", "turboism.cubism.model.write",
                "transaction.enqueue", "turboism.cubism.model.write",
                "transaction.commit", "turboism.cubism.model.write"
            )
        );
    }

    @Test
    void everyOfficialDescriptorCapabilityHasAnExplicitClosedBinding() throws Exception {
        final List<LocalPluginRuntime.LoadedPluginSummary> plugins = List.of(
            plugin(
                "dev.turboism.plugin.catalog",
                List.of(
                    "cubism.project.read", "cubism.workspace.read", "cubism.selection.read",
                    "cubism.parameter.read", "cubism.parameter.write", "cubism.model-tree.read",
                    "cubism.mesh.read", "cubism.deformer.read", "cubism.psd.read", "cubism.clipmask.read",
                    "cubism.texture-atlas.read", "cubism.render.status.read", "cubism.theme.status.read",
                    "ui.context-source.read", "ui.overlay.contribute", "ui.viewport.read", "ui.dialog.contribute",
                    "ui.embedded-panel.contribute", "ui.file-chooser.request", "ui.status.notify",
                    "ui.palette-toolbar.contribute", "ui.main-toolbar.contribute"
                ),
                List.of(
                    "turboism.cubism.project.read", "turboism.cubism.model.read",
                    "turboism.cubism.parameter.read", "turboism.cubism.model.write",
                    "turboism.ui.context-source.read", "turboism.ui.overlay.contribute",
                    "turboism.ui.viewport.read",
                    "turboism.ui.dialog.contribute", "turboism.ui.panel.contribute",
                    "turboism.ui.file-chooser.request", "turboism.ui.status.notify",
                    "turboism.ui.toolbar.palette.contribute", "turboism.ui.toolbar.main.contribute"
                )
            )
        );

        final JsonNode capabilities = capabilityReport(plugins, HostSession.State.SAFE_MODE, false)
            .path("payload")
            .path("capabilities");

        for (JsonNode capability : capabilities) {
            assertFalse(capability.path("operationId").asText().isBlank());
            if (capability.has("permissionId")) {
                assertEquals("GRANTED", capability.path("permissionAvailability").textValue());
            } else {
                assertEquals("NOT_DECLARED", capability.path("permissionAvailability").textValue());
            }
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

        assertEquals("unmapped.capability", capability.path("operationId").textValue());
        assertFalse(capability.path("operationId").textValue().equals(capability.path("capabilityId").textValue()));
        assertFalse(capability.path("operationId").textValue().equals("cubism.parameter.future"));
        assertFalse(capability.has("permissionId"));
        assertEquals("UNKNOWN", capability.path("permissionAvailability").textValue());
        assertEquals("UNKNOWN", capability.path("capabilityAvailability").textValue());
        assertEquals("DECLARED", capability.path("evidence").get(0).path("kind").textValue());
        assertEquals("UNKNOWN", capability.path("evidence").get(0).path("state").textValue());
    }

    @Test
    void modelAndSelectionCatalogBindingsIncludeEveryRealReadOperation() throws Exception {
        final LocalPluginRuntime.LoadedPluginSummary plugin = plugin(
            "dev.turboism.plugin.catalog",
            List.of("cubism.selection.read", "cubism.model-tree.read"),
            List.of("turboism.cubism.model.read")
        );

        final JsonNode capabilities = capabilityReport(List.of(plugin), HostSession.State.SAFE_MODE, false)
            .path("payload")
            .path("capabilities");

        assertCapabilityBindings(
            capabilities,
            "dev.turboism.plugin.catalog",
            "cubism.selection.read",
            Map.of(
                "cubismRead.selection", "turboism.cubism.model.read",
                "selectionQuery.currentSelection", "turboism.cubism.model.read",
                "selectionQuery.selectedIds", "turboism.cubism.model.read",
                "selectionQuery.onSelectionChanged", "turboism.cubism.model.read"
            )
        );
        assertCapabilityBindings(
            capabilities,
            "dev.turboism.plugin.catalog",
            "cubism.model-tree.read",
            Map.of(
                "cubismRead.activeDocument", "turboism.cubism.model.read",
                "cubismRead.activeModel", "turboism.cubism.model.read",
                "cubismRead.modelObjects", "turboism.cubism.model.read",
                "modelHierarchyQuery.currentHierarchy", "turboism.cubism.model.read",
                "modelHierarchyQuery.childrenOf", "turboism.cubism.model.read",
                "modelHierarchyQuery.findNode", "turboism.cubism.model.read"
            )
        );
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
        assertCapabilityOperations(capabilities, pluginId, capabilityId, permissionId, operationId);
    }

    private static void assertCapabilityOperations(
        final JsonNode capabilities,
        final String pluginId,
        final String capabilityId,
        final String permissionId,
        final String... operationIds
    ) {
        final Map<String, String> expected = new java.util.LinkedHashMap<>();
        for (String operationId : operationIds) {
            expected.put(operationId, permissionId);
        }
        assertCapabilityBindings(capabilities, pluginId, capabilityId, expected);
    }

    private static void assertCapabilityBindings(
        final JsonNode capabilities,
        final String pluginId,
        final String capabilityId,
        final Map<String, String> expected
    ) {
        final Map<String, String> found = new java.util.LinkedHashMap<>();
        for (JsonNode capability : capabilities) {
            if (pluginId.equals(capability.path("pluginId").textValue())
                && capabilityId.equals(capability.path("capabilityId").textValue())) {
                found.put(
                    capability.path("operationId").textValue(),
                    capability.path("permissionId").textValue()
                );
            }
        }
        assertEquals(expected, found, "unexpected bindings for " + pluginId + "/" + capabilityId);
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
