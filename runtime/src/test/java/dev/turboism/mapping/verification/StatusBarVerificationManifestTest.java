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
        final Path record = repositoryPath("compatibility/cubism/verification/" + RECORD_NAME);
        final String recordSha = sha256(record);
        assertEquals(StatusBarVerificationManifest.RECORD_5_3_02.recordSha256(), recordSha,
            "manifest record digest must match the reviewed record bytes");

        final JsonNode root = mapper.readTree(record.toFile());
        assertEquals(StatusBarVerificationManifest.RECORD_5_3_02.verificationId(), root.get("verificationId").asText());
        assertEquals(StatusBarVerificationManifest.ADAPTER_SLICE_ID, root.get("adapterSliceId").asText());
        assertEquals(StatusBarVerificationManifest.RECORD_5_3_02.cubismVersion(), root.get("cubismVersion").asText());
        assertEquals(StatusBarVerificationManifest.RECORD_5_3_02.profileId(), root.get("profileId").asText());
        assertEquals(
            StatusBarVerificationManifest.CAPABILITY_IDS,
            Set.copyOf(new HashSet<>(mapper.convertValue(
                root.get("capabilityIds"),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() { }
            )))
        );
        assertEquals(
            StatusBarVerificationManifest.RECORD_5_3_02.artifact().size(),
            root.get("artifact").get("size").asLong()
        );
        assertEquals(
            StatusBarVerificationManifest.RECORD_5_3_02.artifact().sha256(),
            root.get("artifact").get("sha256").asText()
        );
        assertRecordAliases(root, StatusBarVerificationManifest.REQUIRED_ALIASES);
    }

    @Test
    void manifest52MatchesTheReviewedRecordBytesAndAllTwentyOneAliases() throws Exception {
        final Path record = repositoryPath("compatibility/cubism/verification/" + RECORD_NAME_52);
        final String recordSha = sha256(record);
        assertEquals(StatusBarVerificationManifest.RECORD_5_2_03.recordSha256(), recordSha,
            "the 5.2 manifest record digest must match the reviewed 5.2 record bytes");

        final JsonNode root = mapper.readTree(record.toFile());
        assertEquals(StatusBarVerificationManifest.RECORD_5_2_03.verificationId(), root.get("verificationId").asText());
        assertEquals(StatusBarVerificationManifest.ADAPTER_SLICE_ID, root.get("adapterSliceId").asText());
        assertEquals(StatusBarVerificationManifest.RECORD_5_2_03.cubismVersion(), root.get("cubismVersion").asText());
        assertEquals(StatusBarVerificationManifest.RECORD_5_2_03.profileId(), root.get("profileId").asText());
        assertEquals(
            StatusBarVerificationManifest.CAPABILITY_IDS,
            Set.copyOf(new HashSet<>(mapper.convertValue(
                root.get("capabilityIds"),
                new com.fasterxml.jackson.core.type.TypeReference<java.util.List<String>>() { }
            )))
        );
        assertEquals(
            StatusBarVerificationManifest.RECORD_5_2_03.artifact().size(),
            root.get("artifact").get("size").asLong()
        );
        assertEquals(
            StatusBarVerificationManifest.RECORD_5_2_03.artifact().sha256(),
            root.get("artifact").get("sha256").asText()
        );
        assertRecordAliases(root, StatusBarVerificationManifest.REQUIRED_ALIASES);
    }

    private void assertRecordAliases(final JsonNode root, final Set<String> expectedAliases) {
        final Set<String> recordAliases = new HashSet<>();
        for (JsonNode selector : root.get("selectors")) {
            recordAliases.add(selector.get("alias").asText());
            assertEquals("VERIFIED_STATIC", selector.get("status").asText());
        }
        assertEquals(21, recordAliases.size());
        assertEquals(expectedAliases, recordAliases,
            "manifest must authorize exactly the reviewed record selectors");
    }

    @Test
    void manifest52RecordPackAndProfileStayConsistent() throws Exception {
        final JsonNode record = mapper.readTree(
            repositoryPath("compatibility/cubism/verification/" + RECORD_NAME_52).toFile());
        final Set<String> recordMappingIds = new HashSet<>();
        for (JsonNode selector : record.get("selectors")) {
            recordMappingIds.add(selector.get("mappingId").asText());
        }
        assertEquals(21, recordMappingIds.size());

        final JsonNode pack = mapper.readTree(repositoryPath(
            "compatibility/cubism/mapping-packs/draft/cubism-5.2.03-ui-status-bar.json").toFile());
        assertEquals("DRAFT", pack.get("status").asText(),
            "the 5.2 status-bar mapping pack must stay DRAFT");
        assertEquals("5.2.03", pack.get("cubismVersion").asText());
        assertEquals(21, pack.get("entries").size());
        final Set<String> packSemanticNames = new HashSet<>();
        for (JsonNode entry : pack.get("entries")) {
            packSemanticNames.add(entry.get("semanticName").asText());
            assertEquals("cubism-5.2.03", entry.get("profile").asText());
            assertEquals("DRAFT", entry.get("status").asText());
        }
        assertEquals(recordMappingIds, packSemanticNames,
            "record selectors and mapping entries must match bidirectionally");

        final JsonNode profile = mapper.readTree(repositoryPath(
            "compatibility/cubism/profiles/draft/cubism-5.2.03.json").toFile());
        assertEquals("5.2.03", profile.get("cubismVersion").asText());
        boolean listed = false;
        for (JsonNode packId : profile.get("mappingPacks")) {
            listed |= "cubism-5.2.03-ui-status-bar".equals(packId.asText());
        }
        assertTrue(listed, "the 5.2 profile must list the status-bar mapping pack");
    }

    @Test
    void forArtifactServesBothReviewedVersionsAndFailsClosedForAnythingElse() {
        assertEquals(
            StatusBarVerificationManifest.RECORD_5_3_02.cubismVersion(),
            StatusBarVerificationManifest.forArtifact(REVIEWED_5302).cubismVersion()
        );
        assertEquals(
            StatusBarVerificationManifest.RECORD_5_2_03.cubismVersion(),
            StatusBarVerificationManifest.forArtifact(REVIEWED_52).cubismVersion()
        );
        assertEquals(
            StatusBarVerificationManifest.RECORD_5_2_03.recordSha256(),
            StatusBarVerificationManifest.forArtifact(REVIEWED_52).recordSha256()
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> StatusBarVerificationManifest.forArtifact(new HostArtifactDigest(
                1L,
                "0".repeat(64)
            )),
            "unknown artifact must fail closed"
        );
    }

    @Test
    void admissionForArtifactExposesTheReviewedManifestMaterial() {
        final StatusBarVerificationManifest.AdmissionEvidence admission = StatusBarVerificationManifest
            .admissionForArtifact(REVIEWED_5302);
        assertEquals("5.3.02", admission.cubismVersion());
        assertEquals(ReviewedHostArtifacts.CUBISM_5_3_02.size(), admission.artifactSize());
        assertEquals(REVIEWED_5302.sha256(), admission.artifactSha256());
        assertEquals("adapter.editor-ui.status-bar", admission.adapterSliceId());
        assertEquals(StatusBarVerificationManifest.RECORD_5_3_02.recordSha256(), admission.recordSha256());

        final StatusBarVerificationManifest.AdmissionEvidence admission52 = StatusBarVerificationManifest
            .admissionForArtifact(REVIEWED_52);
        assertEquals("5.2.03", admission52.cubismVersion());
        assertEquals(ReviewedHostArtifacts.CUBISM_5_2_03.size(), admission52.artifactSize());
        assertEquals(REVIEWED_52.sha256(), admission52.artifactSha256());
        assertEquals("adapter.editor-ui.status-bar", admission52.adapterSliceId());
        assertEquals(StatusBarVerificationManifest.RECORD_5_2_03.recordSha256(), admission52.recordSha256());
    }

    @Test
    void resolverFactoryFailsClosedBeforeTrustingAnyUnreviewedMaterial() throws Exception {
        final VerifiedStatusBarResolverFactory factory = new VerifiedStatusBarResolverFactory();
        final Path reviewed = repositoryPath("compatibility/cubism/verification/" + RECORD_NAME);

        // Missing artifact: fail closed before the record is even considered.
        assertThrows(java.nio.file.NoSuchFileException.class, () -> factory.create(
            reviewed, Path.of("missing-host-artifact.jar"), getClass().getClassLoader()
        ));

        // Existing but unreviewed artifact: the pinned manifest rejects it before
        // any record bytes are loaded or used.
        final Path foreignArtifact = Files.createTempFile("status-bar-foreign", ".jar");
        Files.writeString(foreignArtifact, "not-the-reviewed-cubism-jar");
        assertThrows(IllegalArgumentException.class, () -> factory.create(
            reviewed, foreignArtifact, getClass().getClassLoader()
        ));
        assertThrows(IllegalArgumentException.class, () -> factory.create(
            repositoryPath("compatibility/cubism/verification/cubism-5.3.02-project-workspace.json"),
            foreignArtifact,
            getClass().getClassLoader()
        ));

        // Invalid record with an unreviewed artifact must also fail closed.
        final Path invalid = Files.createTempFile("status-bar-invalid", ".json");
        Files.writeString(invalid, "{\"format\":\"turboism.static.verification.record\"}");
        assertThrows(IllegalArgumentException.class, () -> factory.create(
            invalid, foreignArtifact, getClass().getClassLoader()
        ));
    }

    private static Path repositoryPath(final String relative) {
        Path root = Path.of("").toAbsolutePath().normalize();
        while (root != null && !Files.exists(root.resolve("settings.gradle.kts"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("repository root not found");
        }
        return root.resolve(relative);
    }

    private static String sha256(final Path file) throws Exception {
        try (java.io.InputStream input = Files.newInputStream(file)) {
            final java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            final byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            final StringBuilder hex = new StringBuilder();
            for (byte value : digest.digest()) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        }
    }
}
