package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WorkspaceControlVerificationRecordTest {
    private static final Path ROOT = locateRepositoryRoot();

    @Test
    void exactRecordsMatchPinnedWorkspaceControlManifests() throws Exception {
        verify("cubism-5.2.03-workspace-control.json", "m.workspace-5.2.03.control.static",
            "adapter.workspace.control.v5_2", "5.2.03", "cubism-5.2.03", ReviewedHostArtifacts.CUBISM_5_2_03.size(),
            ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
            "88811e3a663e595d7b02675fc0e86e486132eb78c96772a90bef3e7d3c7abb94");
        verify("cubism-5.3.02-workspace-control.json", "m.workspace-5.3.02.control.static",
            "adapter.workspace.control.v5_3", "5.3.02", "cubism-5.3.02", ReviewedHostArtifacts.CUBISM_5_3_02.size(),
            ReviewedHostArtifacts.CUBISM_5_3_02.sha256(),
            "cbf5c201267d7aa70d3f82404e9125f61429c7a251457a5a23011c6d6bf27b4f");
    }

    private static void verify(String file, String verificationId, String slice, String version,
                               String profile, long size, String artifactSha, String recordSha) throws Exception {
        Path path = ROOT.resolve("compatibility/cubism/verification").resolve(file);
        JsonNode root = new ObjectMapper().readTree(Files.readString(path));
        assertEquals(recordSha, sha256(path));
        assertEquals(verificationId, root.path("verificationId").asText());
        assertEquals(slice, root.path("adapterSliceId").asText());
        assertEquals(version, root.path("cubismVersion").asText());
        assertEquals(profile, root.path("profileId").asText());
        assertEquals(size, root.path("artifact").path("size").asLong());
        assertEquals(artifactSha, root.path("artifact").path("sha256").asText());
        Set<String> aliases = new HashSet<>();
        root.path("selectors").forEach(selector -> {
            assertEquals("VERIFIED_STATIC", selector.path("status").asText());
            aliases.add(selector.path("alias").asText());
        });
        assertEquals(WorkspaceControlVerificationManifest.REQUIRED_ALIASES, aliases);
        assertEquals(1, root.path("capabilityIds").size());
        assertEquals("cubism.workspace.control", root.path("capabilityIds").get(0).asText());
    }

    private static Path locateRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle.kts"))) {
            current = current.getParent();
        }
        if (current == null) {
            throw new IllegalStateException("Cannot locate repository root from user.dir");
        }
        return current;
    }

    private static String sha256(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        var digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        return java.util.HexFormat.of().formatHex(digest);
    }
}
