package dev.turboism.tests.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class M12CapabilityCatalogSanityTest {

    private static final Path REPO_ROOT = Paths.get(System.getProperty("projectRoot", System.getProperty("user.dir")));
    private static final Path CAPABILITY_DIR = REPO_ROOT.resolve("docs/migration/capabilities");
    private static final Path CAPABILITY_INDEX = CAPABILITY_DIR.resolve("index.md");
    private static final Path CAPABILITY_CATALOG = CAPABILITY_DIR.resolve("capability-catalog.tsv");
    private static final Path CAPABILITY_TEMPLATE = CAPABILITY_DIR.resolve("capability-spec-template.md");
    private static final Path PLUGIN_READINESS_MATRIX = CAPABILITY_DIR.resolve("plugin-readiness-matrix.tsv");
    private static final Path MIGRATION_BOARD = REPO_ROOT.resolve("docs/migration/migration-board.tsv");

    private static final List<String> REQUIRED_CAPABILITY_COLUMNS = List.of(
        "capabilityId", "category", "sdkSurface", "runtimeOwner", "adapterOwner", "permissions",
        "requiresTransaction", "requiresHook", "requiresMapping", "threadingBudget", "fakeHostFixture",
        "diagnostics", "legacyRows", "status"
    );
    private static final Set<String> ALLOWED_CAPABILITY_CATEGORIES = Set.of(
        "read", "write", "ui", "event", "hook-ingress", "diagnostic", "sidecar"
    );
    private static final Set<String> ALLOWED_CAPABILITY_STATUSES = Set.of(
        "planned", "draft", "fake-verified", "adapter-ready", "production-ready", "deferred"
    );
    private static final Set<String> ALLOWED_THREADING_BUDGETS = Set.of(
        "editor-critical-enqueue-only", "ui-short", "plugin-bounded", "sidecar-required"
    );
    private static final Set<String> REQUIRED_CAPABILITY_IDS = Set.of(
        "cubism.project.read", "cubism.selection.read", "cubism.parameter.read", "cubism.model-tree.read",
        "cubism.mesh.read", "cubism.deformer.read", "cubism.psd.read", "cubism.clipmask.read",
        "cubism.texture-atlas.read", "cubism.render.status.read", "cubism.workspace.read", "cubism.theme.status.read",
        "cubism.parameter.write", "cubism.model-tree.write", "cubism.mesh.write", "cubism.deformer.write",
        "cubism.mirror.writeback", "cubism.psd.binding.write", "cubism.clipmask.write", "cubism.canvas.write",
        "cubism.bounding-box.action.write", "ui.context-source.read", "ui.overlay.contribute", "ui.viewport.read",
        "ui.dialog.contribute", "ui.embedded-panel.contribute", "ui.file-chooser.request", "ui.status.notify",
        "ui.palette-toolbar.contribute", "ui.main-toolbar.contribute", "event.project.lifecycle", "event.selection.changed",
        "event.texture-atlas.reinit", "event.render.status.changed", "hook-ingress.project.lifecycle",
        "hook-ingress.selection.changed", "hook-ingress.context-menu.opening", "hook-ingress.texture-atlas.reinit",
        "hook-ingress.viewport.overlay.lifecycle", "hook-ingress.render.status", "hook-ingress.model.tree.changed",
        "hook-ingress.parameter.changed"
    );
    private static final Set<String> HIGH_COUPLING_PLUGINS = Set.of(
        "turboism.ui-theme", "turboism.log-filter", "turboism.main-toolbar", "turboism.context-menu",
        "turboism.project-panel", "turboism.texture-atlas", "turboism.clip-mask", "turboism.bounding-box",
        "turboism.perf-opt", "turboism.render-opt", "turboism.parameter", "turboism.mesh-edit", "turboism.psd-import"
    );

    @Test
    void capabilityCatalogParsableAndCoversM12Foundation() throws Exception {
        List<String> lines = requireLines(CAPABILITY_CATALOG, "M12 capability catalog");
        List<String> columns = Arrays.asList(lines.get(0).split("\t", -1));
        assertEquals(REQUIRED_CAPABILITY_COLUMNS, columns, "Capability catalog columns must match M12 spec fields");

        int idIndex = columns.indexOf("capabilityId");
        int categoryIndex = columns.indexOf("category");
        int statusIndex = columns.indexOf("status");
        Set<String> seenIds = new LinkedHashSet<>();
        Set<String> categories = new HashSet<>();

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            List<String> values = Arrays.asList(line.split("\t", -1));
            assertEquals(columns.size(), values.size(), "Capability row column count must match header: " + line);
            assertCapabilityRow(values, columns, seenIds, categories, idIndex, categoryIndex, statusIndex);
        }

        assertTrue(seenIds.containsAll(REQUIRED_CAPABILITY_IDS),
            "M12 catalog must cover every initial read/write/UI/event/hook-ingress capability");
        assertTrue(categories.containsAll(Set.of("read", "write", "ui", "event", "hook-ingress")),
            "M12 catalog must include read, write, UI, event, and hook-ingress categories");
    }

    @Test
    void capabilityTemplateAndPluginReadinessMatrixExist() throws Exception {
        String template = Files.readString(CAPABILITY_TEMPLATE);
        for (String column : REQUIRED_CAPABILITY_COLUMNS) {
            assertTrue(template.contains(column), "Capability template must document field: " + column);
        }

        List<String> lines = requireLines(PLUGIN_READINESS_MATRIX, "M12 plugin readiness matrix");
        List<String> columns = Arrays.asList(lines.get(0).split("\t", -1));
        assertEquals(List.of("plugin", "legacyRows", "requiredCapabilities", "readiness", "productionBlockedBy", "nextSlice"),
            columns, "Plugin readiness matrix columns must match M12.1 gate shape");

        Set<String> seenPlugins = new LinkedHashSet<>();
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            List<String> values = Arrays.asList(line.split("\t", -1));
            assertEquals(columns.size(), values.size(), "Readiness row column count must match header: " + line);
            assertReadinessRow(values, seenPlugins);
        }

        assertTrue(seenPlugins.containsAll(HIGH_COUPLING_PLUGINS),
            "Every high-coupling legacy plugin must have M12 capability prerequisites");
    }

    @Test
    void governanceIndexDocumentsStatusVocabularyAndProductionGate() throws Exception {
        String index = Files.readString(CAPABILITY_INDEX);
        for (String status : ALLOWED_CAPABILITY_STATUSES) {
            assertTrue(index.contains(status), "Governance index must document capability status: " + status);
        }
        for (String readiness : Set.of("shell-ready", "fake-ready", "adapter-ready", "production-ready", "blocked")) {
            assertTrue(index.contains(readiness), "Governance index must document plugin readiness: " + readiness);
        }
        assertTrue(index.contains("No plugin may be marked `production-ready`"),
            "Governance index must keep production readiness behind explicit evidence");
        assertTrue(index.contains("L0") && index.contains("L1"),
            "Governance index must document L0/L1 quarantine rules");
    }

    @Test
    void catalogCannotPromoteQuarantinedEvidenceOrMissingSurfaces() throws Exception {
        Map<String, MigrationEvidence> migrationEvidence = migrationEvidenceById();
        List<String> lines = requireLines(CAPABILITY_CATALOG, "M12 capability catalog");
        List<String> columns = Arrays.asList(lines.get(0).split("\t", -1));

        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> values = Arrays.asList(lines.get(i).split("\t", -1));
            String capabilityId = value(values, columns, "capabilityId");
            String status = value(values, columns, "status");
            assertLegacyEvidenceCeiling(capabilityId, status, value(values, columns, "legacyRows"), migrationEvidence);
            assertSurfaceExistsWhenPromoted(capabilityId, status, value(values, columns, "sdkSurface"));
        }
    }

    private static List<String> requireLines(Path path, String label) throws Exception {
        assertTrue(Files.exists(path), label + " must exist");
        List<String> lines = Files.readAllLines(path);
        assertFalse(lines.isEmpty(), label + " must not be empty");
        return lines;
    }

    private static void assertCapabilityRow(
        List<String> values,
        List<String> columns,
        Set<String> seenIds,
        Set<String> categories,
        int idIndex,
        int categoryIndex,
        int statusIndex
    ) {
        String capabilityId = values.get(idIndex).trim();
        assertTrue(capabilityId.matches("^[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)+$"),
            "Capability id must be a stable dotted identifier: " + capabilityId);
        assertTrue(seenIds.add(capabilityId), "Duplicate capability id: " + capabilityId);

        String category = values.get(categoryIndex).trim();
        assertTrue(ALLOWED_CAPABILITY_CATEGORIES.contains(category), "Unknown capability category for " + capabilityId);
        categories.add(category);

        assertRequired(values, columns, "sdkSurface", capabilityId);
        assertRequired(values, columns, "runtimeOwner", capabilityId);
        assertRequired(values, columns, "permissions", capabilityId);
        assertBoolean(values, columns, "requiresTransaction", capabilityId);
        assertBoolean(values, columns, "requiresHook", capabilityId);
        assertBoolean(values, columns, "requiresMapping", capabilityId);
        assertTrue(ALLOWED_THREADING_BUDGETS.contains(value(values, columns, "threadingBudget")),
            "Invalid threading budget for " + capabilityId);
        assertRequired(values, columns, "fakeHostFixture", capabilityId);
        assertRequired(values, columns, "diagnostics", capabilityId);
        assertRequired(values, columns, "legacyRows", capabilityId);
        assertTrue(ALLOWED_CAPABILITY_STATUSES.contains(values.get(statusIndex).trim()),
            "Invalid capability status for " + capabilityId);
    }

    private static void assertReadinessRow(List<String> values, Set<String> seenPlugins) {
        String plugin = values.get(0).trim();
        seenPlugins.add(plugin);
        assertFalse(values.get(2).isBlank(), "Required capabilities must be listed for " + plugin);
        assertTrue(Set.of("shell-ready", "fake-ready", "adapter-ready", "production-ready", "blocked").contains(values.get(3).trim()),
            "Unexpected readiness value for " + plugin);
        if (!"production-ready".equals(values.get(3).trim())) {
            assertFalse(values.get(4).isBlank(), "Non-production plugin must list blockers: " + plugin);
        }
    }

    private static void assertRequired(List<String> values, List<String> columns, String field, String capabilityId) {
        assertFalse(value(values, columns, field).isBlank(), field + " required for " + capabilityId);
    }

    private static void assertBoolean(List<String> values, List<String> columns, String field, String capabilityId) {
        assertTrue(Set.of("true", "false").contains(value(values, columns, field)), field + " must be boolean for " + capabilityId);
    }

    private static String value(List<String> values, List<String> columns, String field) {
        return values.get(columns.indexOf(field)).trim();
    }

    private static Map<String, MigrationEvidence> migrationEvidenceById() throws Exception {
        List<String> lines = requireLines(MIGRATION_BOARD, "migration board");
        List<String> columns = Arrays.asList(lines.get(0).split("\t", -1));
        Map<String, MigrationEvidence> evidence = new HashMap<>();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).isBlank()) continue;
            List<String> values = Arrays.asList(lines.get(i).split("\t", -1));
            evidence.put(value(values, columns, "id"), new MigrationEvidence(
                value(values, columns, "reuseLevel"),
                value(values, columns, "status")
            ));
        }
        return evidence;
    }

    private static void assertLegacyEvidenceCeiling(
        String capabilityId,
        String capabilityStatus,
        String legacyRows,
        Map<String, MigrationEvidence> migrationEvidence
    ) {
        for (String rowId : legacyRows.split(";")) {
            MigrationEvidence evidence = migrationEvidence.get(rowId.trim());
            assertNotNull(evidence, capabilityId + " references unknown migration row: " + rowId);
            if ("L0".equals(evidence.reuseLevel()) || "NEVER".equals(evidence.status())) {
                assertEquals("deferred", capabilityStatus, capabilityId + " must defer L0/NEVER evidence: " + rowId);
            }
            if ("L1".equals(evidence.reuseLevel()) || "QUARANTINE".equals(evidence.status())) {
                assertTrue(statusRank(capabilityStatus) <= statusRank("planned"),
                    capabilityId + " must not promote L1/QUARANTINE evidence above planned: " + rowId);
            }
        }
    }

    private static void assertSurfaceExistsWhenPromoted(String capabilityId, String status, String sdkSurface) {
        if (statusRank(status) < statusRank("draft") || sdkSurface.startsWith("internal semantic ingress ")) {
            return;
        }
        String relativePath = sdkSurface.replace('.', '/') + ".java";
        assertTrue(
            Files.exists(REPO_ROOT.resolve("sdk/src/main/java").resolve(relativePath))
                || Files.exists(REPO_ROOT.resolve("runtime/src/main/java").resolve(relativePath)),
            capabilityId + " is " + status + " but surface does not exist: " + sdkSurface
        );
    }

    private static int statusRank(String status) {
        return switch (status) {
            case "deferred" -> -1;
            case "planned" -> 0;
            case "draft" -> 1;
            case "fake-verified" -> 2;
            case "adapter-ready" -> 3;
            case "production-ready" -> 4;
            default -> -2;
        };
    }

    private record MigrationEvidence(String reuseLevel, String status) {
    }
}
