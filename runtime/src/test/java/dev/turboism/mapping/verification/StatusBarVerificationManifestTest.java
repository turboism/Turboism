package dev.turboism.mapping.verification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatusBarVerificationManifestTest {

    private static final String RECORD_NAME = "cubism-5.3.02-ui-status-bar.json";
    private static final String RECORD_NAME_52 = "cubism-5.2.03-ui-status-bar.json";
    private static final HostArtifactDigest REVIEWED_5302 = ReviewedHostArtifacts.CUBISM_5_3_02;
    private static final HostArtifactDigest REVIEWED_52 = ReviewedHostArtifacts.CUBISM_5_2_03;

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void manifestMatchesTheReviewedRecordBytesAndAllTwentyOneAliases() throws Exception {
        assertRecord(RECORD_NAME, StatusBarVerificationManifest.RECORD_5_3_02);
    }

    @Test
    void manifest52MatchesTheReviewedRecordBytesAndAllTwentyOneAliases() throws Exception {
        assertRecord(RECORD_NAME_52, StatusBarVerificationManifest.RECORD_5_2_03);
    }

    private void assertRecord(
        final String name,
        final ReviewedSliceRecord expected
    ) throws Exception {
        final Path record = repositoryPath("compatibility/cubism/verification/" + name);
        assertEquals(expected.recordSha256(), sha256(record));
        final JsonNode root = mapper.readTree(record.toFile());
        assertEquals(expected.verificationId(), root.get("verificationId").asText());
        assertEquals(StatusBarVerificationManifest.ADAPTER_SLICE_ID, root.get("adapterSliceId").asText());
        assertEquals(expected.cubismVersion(), root.get("cubismVersion").asText());
        assertEquals(expected.profileId(), root.get("profileId").asText());
        assertEquals(expected.artifact().size(), root.get("artifact").get("size").asLong());
        assertEquals(expected.artifact().sha256(), root.get("artifact").get("sha256").asText());
        final Set<String> aliases = new HashSet<>();
        root.get("selectors").forEach(selector -> aliases.add(selector.get("alias").asText()));
        assertEquals(StatusBarVerificationManifest.REQUIRED_ALIASES, aliases);
    }

    @Test
    void manifest52RecordPackAndProfileStayConsistent() throws Exception {
        final JsonNode record = mapper.readTree(repositoryPath(
            "compatibility/cubism/verification/" + RECORD_NAME_52
        ).toFile());
        final Set<String> recordMappingIds = new HashSet<>();
        record.get("selectors").forEach(selector -> recordMappingIds.add(selector.get("mappingId").asText()));

        final JsonNode pack = mapper.readTree(repositoryPath(
            "compatibility/cubism/mapping-packs/draft/cubism-5.2.03-ui-status-bar.json"
        ).toFile());
        final Set<String> packSemanticNames = new HashSet<>();
        pack.get("entries").forEach(entry -> packSemanticNames.add(entry.get("semanticName").asText()));
        assertEquals(recordMappingIds, packSemanticNames);

        final JsonNode profile = mapper.readTree(repositoryPath(
            "compatibility/cubism/profiles/draft/cubism-5.2.03.json"
        ).toFile());
        boolean listed = false;
        for (JsonNode packId : profile.get("mappingPacks")) {
            listed |= "cubism-5.2.03-ui-status-bar".equals(packId.asText());
        }
        assertTrue(listed);
    }

    @Test
    void forArtifactServesReviewedVersionsAndKeeps5303Closed() {
        assertEquals("5.3.02", StatusBarVerificationManifest.forArtifact(REVIEWED_5302).cubismVersion());
        assertEquals("5.2.03", StatusBarVerificationManifest.forArtifact(REVIEWED_52).cubismVersion());
        assertEquals("5.3.03", StatusBarVerificationManifest.forArtifact(
            ReviewedHostArtifacts.CUBISM_5_3_03
        ).cubismVersion());
        assertThrows(IllegalArgumentException.class, () -> StatusBarVerificationManifest.forArtifact(
            new HostArtifactDigest(1L, "0".repeat(64))
        ));
        assertTrue(!ReviewedHostArtifacts.admitsFullRuntime("5.3.03"));
    }

    private static Path repositoryPath(final String relative) {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("repository root not found");
        return root.resolve(relative);
    }

    private static String sha256(final Path file) throws Exception {
        return java.util.HexFormat.of().formatHex(
            java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file))
        );
    }
}
