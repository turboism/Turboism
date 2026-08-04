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
        verify("cubism-5.2-workspace-control.json", "m.workspace-5.2.03.control.static",
            "adapter.workspace.control.v5_2", "5.2.03", "cubism-5.2", 40_805_584L,
            "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
            "8b001802fa672ce2f053ab516af9c38b2a2a08296fc663e9adf352e88c7dbf36");
        verify("cubism-5.3.02-workspace-control.json", "m.workspace-5.3.02.control.static",
            "adapter.workspace.control.v5_3", "5.3.02", "cubism-5.3.02", 41_922_739L,
            "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21",
            "7c675de8b23e63e6de14ae6c67403717d3b64fc8eefab54ac4124fffb3633f16");
    }

    private static void verify(String file, String verificationId, String slice, String version,
                               String profile, long size, String artifactSha, String recordSha) throws Exception {
        Path path = ROOT.resolve("cubism-ref/verification").resolve(file);
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
