package dev.turboism.preview.report;

import com.fasterxml.jackson.databind.JsonNode;
import dev.turboism.adapter.host.HostSession;
import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.core.lifecycle.PluginLifecycleState;
import dev.turboism.i18n.RuntimePluginLocalization;
import dev.turboism.preview.LocalPluginRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewCapabilityCatalogRegistryTest {

    private static final Path CAPABILITY_CATALOG = Path.of("..", "docs/migration/capabilities/capability-catalog.tsv")
        .toAbsolutePath()
        .normalize();
    private static final String UNMAPPED_OPERATION = "unmapped.capability";

    @TempDir
    Path temporary;

    @Test
    void everyCanonicalCatalogCapabilityHasAnExplicitMappedOrUnmappedPreviewPolicy() throws Exception {
        final List<String> capabilityIds = Files.readAllLines(CAPABILITY_CATALOG).stream()
            .skip(1)
            .filter(line -> !line.isBlank())
            .map(line -> line.split("\\t", -1)[0])
            .toList();
        assertEquals(63, capabilityIds.size(), "canonical catalog size changed; update this closure test deliberately");

        final JsonNode capabilities = capabilityReport(capabilityIds)
            .path("payload")
            .path("capabilities");
        final Map<String, List<JsonNode>> entriesByCapability = new java.util.LinkedHashMap<>();
        for (JsonNode entry : capabilities) {
            entriesByCapability.computeIfAbsent(entry.path("capabilityId").textValue(), ignored -> new ArrayList<>())
                .add(entry);
        }

        assertEquals(Set.copyOf(capabilityIds), entriesByCapability.keySet());
        for (String capabilityId : capabilityIds) {
            final List<JsonNode> entries = entriesByCapability.get(capabilityId);
            assertFalse(entries.isEmpty(), "missing preview policy for " + capabilityId);
            final boolean unmapped = entries.stream()
                .allMatch(entry -> UNMAPPED_OPERATION.equals(entry.path("operationId").textValue()));
            if (unmapped) {
                assertEquals(1, entries.size(), "unmapped policy must have one explicit sentinel entry");
                final JsonNode entry = entries.get(0);
                assertFalse(entry.has("permissionId"));
                assertEquals("UNKNOWN", entry.path("capabilityAvailability").textValue());
                assertEquals("UNKNOWN", entry.path("permissionAvailability").textValue());
            } else {
                assertTrue(entries.stream().noneMatch(
                    entry -> UNMAPPED_OPERATION.equals(entry.path("operationId").textValue())
                ));
                for (JsonNode entry : entries) {
                    assertFalse(entry.path("operationId").textValue().isBlank());
                    assertTrue(entry.has("permissionId"));
                }
            }
        }
    }

    private JsonNode capabilityReport(final List<String> capabilityIds) throws Exception {
        final Path verification = temporary.resolve("state/verification.json");
        Files.createDirectories(verification.getParent());
        Files.writeString(verification, "static verification record");
        final LocalPluginRuntime.LoadedPluginSummary plugin = new LocalPluginRuntime.LoadedPluginSummary(
            "dev.turboism.plugin.catalog-policy",
            "Catalog Policy",
            "1.0.0",
            PluginLifecycleState.ENABLED,
            temporary.resolve("plugins/catalog-policy.jar"),
            capabilityIds,
            List.of(
                "turboism.cubism.project.read", "turboism.cubism.model.read",
                "turboism.cubism.model.write", "turboism.cubism.parameter.read",
                "turboism.ui.context-source.read", "turboism.ui.overlay.contribute",
                "turboism.ui.viewport.read", "turboism.ui.dialog.contribute",
                "turboism.ui.panel.contribute", "turboism.ui.file-chooser.request",
                "turboism.ui.status.notify", "turboism.ui.toolbar.palette.contribute",
                "turboism.ui.toolbar.main.contribute"
            ),
            new RuntimePluginLocalization.ReportSnapshot(
                "dev.turboism.plugin.catalog-policy",
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
        return PreviewReportSnapshotFactory.create(
            "catalog-policy-test",
            Instant.parse("2026-07-17T00:00:00Z"),
            temporary,
            HostSession.State.SAFE_MODE,
            temporary.resolve("Cubism.exe"),
            verification,
            new LocalPluginRuntime.LoadReport(List.of(plugin), List.of(), List.of()),
            List.of(plugin),
            false
        ).get(PreviewReportType.CAPABILITY);
    }
}
