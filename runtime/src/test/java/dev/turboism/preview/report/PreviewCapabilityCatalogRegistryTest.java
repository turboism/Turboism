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
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewCapabilityCatalogRegistryTest {

    private static final String UNMAPPED_OPERATION = "unmapped.capability";

    @TempDir
    Path temporary;

    @Test
    void everyCanonicalCatalogCapabilityHasAnExplicitMappedOrUnmappedPreviewPolicy() throws Exception {
        final List<String> capabilityIds = PreviewReportSnapshotFactory.canonicalCapabilityIds().stream()
            .sorted()
            .toList();
        assertEquals(69, capabilityIds.size(), "canonical catalog size changed; update this closure test deliberately");

        final JsonNode capabilities = capabilityReport(capabilityIds)
            .path("payload")
            .path("capabilities");
        final Map<String, List<JsonNode>> entriesByCapability = entriesByCapability(capabilities);
        final Set<String> canonicalCapabilities = Set.copyOf(capabilityIds);
        final Set<String> mappedCapabilities = entriesByCapability.entrySet().stream()
            .filter(entry -> entry.getValue().stream().noneMatch(this::isUnmappedEntry))
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());
        final Set<String> knownUnmappedCapabilities = entriesByCapability.entrySet().stream()
            .filter(entry -> entry.getValue().stream().allMatch(this::isUnmappedEntry))
            .map(Map.Entry::getKey)
            .collect(Collectors.toUnmodifiableSet());

        assertEquals(69, canonicalCapabilities.size());
        assertEquals(canonicalCapabilities, entriesByCapability.keySet());
        assertTrue(
            java.util.Collections.disjoint(mappedCapabilities, knownUnmappedCapabilities),
            "mapped and known-unmapped capability sets must not overlap"
        );
        assertEquals(canonicalCapabilities, union(mappedCapabilities, knownUnmappedCapabilities));
        for (String capabilityId : capabilityIds) {
            final List<JsonNode> entries = entriesByCapability.get(capabilityId);
            assertFalse(entries.isEmpty(), "missing preview policy for " + capabilityId);
            if (knownUnmappedCapabilities.contains(capabilityId)) {
                assertEquals(1, entries.size(), "unmapped policy must have one explicit sentinel entry");
                final JsonNode entry = entries.get(0);
                assertFalse(entry.has("permissionId"));
                assertEquals("UNKNOWN", entry.path("capabilityAvailability").textValue());
                assertEquals("UNKNOWN", entry.path("permissionAvailability").textValue());
            } else {
                assertTrue(entries.stream().noneMatch(this::isUnmappedEntry));
                for (JsonNode entry : entries) {
                    assertFalse(entry.path("operationId").textValue().isBlank());
                    assertTrue(entry.has("permissionId"));
                }
            }
        }
    }

    @Test
    void nonCanonicalUnknownCapabilityUsesFallbackWithoutChangingCanonicalRegistry() throws Exception {
        final List<String> canonicalCapabilities = PreviewReportSnapshotFactory.canonicalCapabilityIds().stream()
            .sorted()
            .toList();
        final String unknownCapability = "cubism.future.unknown";
        final List<String> requestedCapabilities = new ArrayList<>(canonicalCapabilities);
        requestedCapabilities.add(unknownCapability);

        final Map<String, List<JsonNode>> entries = entriesByCapability(
            capabilityReport(requestedCapabilities).path("payload").path("capabilities")
        );

        assertEquals(Set.copyOf(requestedCapabilities), entries.keySet());
        assertEquals(1, entries.get(unknownCapability).size());
        final JsonNode fallback = entries.get(unknownCapability).get(0);
        assertEquals(UNMAPPED_OPERATION, fallback.path("operationId").textValue());
        assertFalse(fallback.has("permissionId"));
        assertEquals("UNKNOWN", fallback.path("capabilityAvailability").textValue());
        assertEquals("UNKNOWN", fallback.path("permissionAvailability").textValue());
    }

    private Map<String, List<JsonNode>> entriesByCapability(final JsonNode capabilities) {
        final Map<String, List<JsonNode>> entries = new java.util.LinkedHashMap<>();
        for (JsonNode entry : capabilities) {
            entries.computeIfAbsent(entry.path("capabilityId").textValue(), ignored -> new ArrayList<>())
                .add(entry);
        }
        return entries;
    }

    private boolean isUnmappedEntry(final JsonNode entry) {
        return UNMAPPED_OPERATION.equals(entry.path("operationId").textValue());
    }

    private static Set<String> union(final Set<String> left, final Set<String> right) {
        final Set<String> union = new java.util.HashSet<>(left);
        union.addAll(right);
        return Set.copyOf(union);
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
                "turboism.cubism.parameter.read", "turboism.cubism.model.write",
                "turboism.ui.context-source.read", "turboism.ui.overlay.contribute",
                "turboism.ui.viewport.read", "turboism.ui.dialog.contribute", "turboism.ui.dialog.automate",
                "turboism.ui.panel.contribute", "turboism.ui.file-chooser.request",
                "turboism.ui.status.notify", "turboism.ui.toolbar.palette.contribute",
                "turboism.ui.toolbar.main.contribute", "turboism.cubism.recent-file.read",
                "turboism.ui.recent-preview.contribute"
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
