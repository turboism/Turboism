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
    private static final Path ROOT = Path.of(System.getProperty("user.dir")).getParent();

    @Test
    void exactRecordsMatchPinnedWorkspaceControlManifests() throws Exception {
        verify("cubism-5.2-workspace-control.json", "m.workspace-5.2.03.control.static",
            "adapter.workspace.control.v5_2", "5.2.03", "cubism-5.2", 40_805_584L,
            "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
            "b49e80ca37f3173379551ca33452b10c8869345cf746386174d43a6f11eae759");
        verify("cubism-5.3.02-workspace-control.json", "m.workspace-5.3.02.control.static",
            "adapter.workspace.control.v5_3", "5.3.02", "cubism-5.3.02", 41_922_739L,
            "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21",
            "7fab462234b9055d2634691424ae7e45e7ac96ea9247da6292c54bb8a32d5619");
    }

    private static void verify(String file, String verificationId, String slice, String version,
                               String profile, long size, String artifactSha, String recordSha) throws Exception {
        Path path = ROOT.resolve("docs/migration/verification/static").resolve(file);
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

    private static String sha256(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        var digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes);
        return java.util.HexFormat.of().formatHex(digest);
    }
}
