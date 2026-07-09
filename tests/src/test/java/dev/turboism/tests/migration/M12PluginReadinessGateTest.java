package dev.turboism.tests.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M12PluginReadinessGateTest {

    private static final Path REPO_ROOT = Paths.get(System.getProperty("projectRoot", System.getProperty("user.dir")));
    private static final Path CATALOG = REPO_ROOT.resolve("docs/migration/capabilities/capability-catalog.tsv");
    private static final Path MATRIX = REPO_ROOT.resolve("docs/migration/capabilities/plugin-readiness-matrix.tsv");

    @Test
    void readinessMatrixReferencesOnlyCatalogedCapabilitiesAndKeepsHighRiskPluginsBlocked() throws Exception {
        Map<String, String> capabilityStatuses = catalogCapabilityStatuses();
        List<String> lines = Files.readAllLines(MATRIX);
        int productionReadyCount = 0;
        int blockedHighRiskCount = 0;

        for (int i = 1; i < lines.size(); i++) {
            List<String> row = Arrays.asList(lines.get(i).split("\t", -1));
            String plugin = row.get(0);
            String readiness = row.get(3);
            String blocker = row.get(4);
            for (String capabilityId : row.get(2).split(";")) {
                assertTrue(capabilityStatuses.containsKey(capabilityId), plugin + " references missing capability " + capabilityId);
                assertTrue(
                    statusRank(capabilityStatuses.get(capabilityId)) >= requiredRank(readiness),
                    plugin + " is " + readiness + " but " + capabilityId + " is only " + capabilityStatuses.get(capabilityId)
                );
            }
            if ("production-ready".equals(readiness)) {
                productionReadyCount++;
            } else {
                assertFalse(blocker.isBlank(), plugin + " must list production blockers");
            }
            if (Set.of("turboism.parameter", "turboism.mesh-edit", "turboism.psd-import").contains(plugin) && "blocked".equals(readiness)) {
                blockedHighRiskCount++;
            }
        }

        assertEquals(0, productionReadyCount, "M12 must not mark plugins production-ready");
        assertEquals(3, blockedHighRiskCount, "High-coupling write plugins must remain blocked");
    }

    private static Map<String, String> catalogCapabilityStatuses() throws Exception {
        Map<String, String> statuses = new HashMap<>();
        List<String> lines = Files.readAllLines(CATALOG);
        for (int i = 1; i < lines.size(); i++) {
            String[] row = lines.get(i).split("\t", -1);
            statuses.put(row[0], row[13]);
        }
        return statuses;
    }

    private static int requiredRank(String readiness) {
        return switch (readiness) {
            case "shell-ready", "blocked" -> 0;
            case "fake-ready" -> 2;
            case "adapter-ready" -> 3;
            case "production-ready" -> 4;
            default -> 99;
        };
    }

    private static int statusRank(String status) {
        return switch (status) {
            case "planned" -> 0;
            case "draft" -> 1;
            case "fake-verified" -> 2;
            case "adapter-ready" -> 3;
            case "production-ready" -> 4;
            case "deferred" -> -1;
            default -> -2;
        };
    }
}
