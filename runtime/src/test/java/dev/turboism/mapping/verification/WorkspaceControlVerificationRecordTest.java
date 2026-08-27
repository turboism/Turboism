package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class WorkspaceControlVerificationRecordTest {
    private static final Path ROOT = locateRepositoryRoot();

    @Test
    void exactRecordsMatchPinnedWorkspaceControlManifests() throws Exception {
        verify("cubism-5.2.03-workspace-control.json", "5.2.03", ReviewedHostArtifacts.CUBISM_5_2_03);
        verify("cubism-5.3.02-workspace-control.json", "5.3.02", ReviewedHostArtifacts.CUBISM_5_3_02);
        verify("cubism-5.3.03-workspace-control.json", "5.3.03", ReviewedHostArtifacts.CUBISM_5_3_03);
        assertFalse(ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    private static void verify(
        final String file,
        final String version,
        final HostArtifactDigest artifact
    ) throws Exception {
        final Path path = ROOT.resolve("compatibility/cubism/verification").resolve(file);
        final JsonNode root = new ObjectMapper().readTree(Files.readString(path));
        assertEquals(version, root.path("cubismVersion").asText());
        assertEquals(artifact.size(), root.path("artifact").path("size").asLong());
        assertEquals(artifact.sha256(), root.path("artifact").path("sha256").asText());
        final Set<String> aliases = new HashSet<>();
        root.path("selectors").forEach(selector -> aliases.add(selector.path("alias").asText()));
        assertEquals(WorkspaceControlVerificationManifest.REQUIRED_ALIASES, aliases);
        assertEquals("cubism.workspace.control", root.path("capabilityIds").get(0).asText());
    }

    private static Path locateRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Cannot locate repository root");
        return current;
    }
}
