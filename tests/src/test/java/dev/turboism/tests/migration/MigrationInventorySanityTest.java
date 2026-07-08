package dev.turboism.tests.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sanity tests for the tracked migration inventory.
 */
class MigrationInventorySanityTest {

    private static final Path REPO_ROOT = Paths.get(System.getProperty("projectRoot", System.getProperty("user.dir")));
    private static final Path MIGRATION_DIR = REPO_ROOT.resolve("docs/migration");
    private static final Path MIGRATION_BOARD = MIGRATION_DIR.resolve("migration-board.tsv");

    private static final Set<String> ALLOWED_REUSE_LEVELS = Set.of("L0", "L1", "L2", "L3", "L4");
    private static final Set<String> ALLOWED_STATUSES = Set.of("NEVER", "QUARANTINE", "PENDING", "DRAFT", "DRAFT_IMPORTED", "DEFERRED", "READY", "COMPLETE");
    private static final Set<String> KNOWN_PHASES = Set.of(
        "M1_LEGACY_INVENTORY",
        "M2_SCHEMA_CONFIG_DIAGNOSTICS",
        "M3_MAPPING_PROFILE_DRAFT",
        "M3+",
        "M4_HOOKSPEC_DRYRUN",
        "M5_PROFILE_DETECTION",
        "M6_READONLY_CUBISM_FACADE",
        "M6+ after transaction design",
        "M7_READONLY_SERVICE",
        "M7.5_RUNTIME_ISOLATION",
        "M8_LOW_RISK_PLUGIN",
        "M9_RELEASE_VALIDATION",
        "M8_LOW_RISK_PLUGIN_SHELLS",
        "M10_WRITE_SERVICE",
        "M11_HIGH_COUPLING_PLUGIN",
        "P0/P1",
        "P0/P1 extension seam",
        "P1/P2",
        "P1/P2 after PSD action contract",
        "P2/P3",
        "P2/P3 after Mesh/Mirror seams",
        "P2/P3 after parameter workflow seams",
        "NEVER"
    );
    private static final Set<String> IMPLEMENTATION_PHASES = Set.of(
        "M3_MAPPING_PROFILE_DRAFT",
        "M3+",
        "M4_HOOKSPEC_DRYRUN",
        "M5_PROFILE_DETECTION",
        "M6_READONLY_CUBISM_FACADE",
        "M6+ after transaction design",
        "M7_READONLY_SERVICE",
        "M7.5_RUNTIME_ISOLATION",
        "M8_LOW_RISK_PLUGIN",
        "M9_RELEASE_VALIDATION",
        "M8_LOW_RISK_PLUGIN_SHELLS",
        "M10_WRITE_SERVICE",
        "M11_HIGH_COUPLING_PLUGIN",
        "P0/P1",
        "P0/P1 extension seam",
        "P1/P2",
        "P1/P2 after PSD action contract",
        "P2/P3",
        "P2/P3 after Mesh/Mirror seams",
        "P2/P3 after parameter workflow seams"
    );
    private static final List<String> PLAN_FILES = List.of(
        "docs/migration/plans/m2-schema-governance-gates-plan.md",
        "docs/migration/plans/m3-mapping-profile-draft-import-plan.md",
        "docs/migration/plans/m4-hookspec-dryrun-plan.md",
        "docs/migration/plans/m5-profile-resolver-plan.md",
        "docs/migration/plans/m6-readonly-cubism-facade-plan.md",
        "docs/migration/plans/m8-low-risk-plugin-shells-plan.md"
    );

    @Test
    void migrationBoardParsableAndValid() throws Exception {
        assertTrue(Files.exists(MIGRATION_BOARD), "migration-board.tsv must exist");

        List<String> lines = Files.readAllLines(MIGRATION_BOARD);
        assertFalse(lines.isEmpty(), "migration-board.tsv must not be empty");

        String header = lines.get(0);
        List<String> columns = Arrays.asList(header.split("\t", -1));
        List<String> requiredColumns = List.of("id", "phase", "feature", "legacyPath", "reuseLevel", "target", "status", "notes");
        assertTrue(columns.containsAll(requiredColumns),
            "migration-board.tsv header must contain required columns: " + requiredColumns);

        int idIndex = columns.indexOf("id");
        int phaseIndex = columns.indexOf("phase");
        int reuseLevelIndex = columns.indexOf("reuseLevel");
        int statusIndex = columns.indexOf("status");
        int featureIndex = columns.indexOf("feature");

        Set<String> seenIds = new LinkedHashSet<>();
        int l0ImplementationCount = 0;
        int rowCount = 0;

        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            List<String> values = Arrays.asList(line.split("\t", -1));
            assertEquals(columns.size(), values.size(),
                "Row " + (i + 1) + " column count does not match header: " + line);

            String id = values.get(idIndex).trim();
            assertFalse(id.isEmpty(), "Row " + (i + 1) + " has empty id");
            assertTrue(seenIds.add(id), "Duplicate id in migration board: " + id);

            String reuseLevel = values.get(reuseLevelIndex).trim();
            assertTrue(ALLOWED_REUSE_LEVELS.contains(reuseLevel),
                "Invalid reuseLevel '" + reuseLevel + "' for id " + id);

            String phase = values.get(phaseIndex).trim();
            assertTrue(KNOWN_PHASES.contains(phase),
                "Unknown phase '" + phase + "' for id " + id);

            String status = values.get(statusIndex).trim();
            assertTrue(ALLOWED_STATUSES.contains(status),
                "Invalid status '" + status + "' for id " + id);

            if ("L0".equals(reuseLevel)) {
                assertEquals("NEVER", status, "L0 row must have status NEVER: " + id);
                if (IMPLEMENTATION_PHASES.contains(phase)) {
                    l0ImplementationCount++;
                }
            }

            rowCount++;
        }

        assertEquals(0, l0ImplementationCount, "L0 rows must not be represented as implementation slices");
        assertTrue(rowCount > 0, "migration-board.tsv must contain at least one data row");
    }

    @Test
    void planFilesExist() {
        for (String plan : PLAN_FILES) {
            Path path = REPO_ROOT.resolve(plan);
            assertTrue(Files.exists(path), "Plan file must exist: " + plan);
        }
    }
}
